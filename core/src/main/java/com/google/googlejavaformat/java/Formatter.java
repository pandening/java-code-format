/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.googlejavaformat.java;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import com.google.common.io.CharSink;
import com.google.common.io.CharSource;
import com.google.errorprone.annotations.Immutable;
import com.google.googlejavaformat.Doc;
import com.google.googlejavaformat.DocBuilder;
import com.google.googlejavaformat.FormattingError;
import com.google.googlejavaformat.Newlines;
import com.google.googlejavaformat.Op;
import com.google.googlejavaformat.OpsBuilder;
import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.DiagnosticCollector;
import org.openjdk.javax.tools.DiagnosticListener;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.javax.tools.SimpleJavaFileObject;
import org.openjdk.javax.tools.StandardLocation;
import org.openjdk.tools.javac.file.JavacFileManager;
import org.openjdk.tools.javac.main.Option;
import org.openjdk.tools.javac.parser.JavacParser;
import org.openjdk.tools.javac.parser.ParserFactory;
import org.openjdk.tools.javac.tree.JCTree.JCCompilationUnit;
import org.openjdk.tools.javac.util.Context;
import org.openjdk.tools.javac.util.Log;
import org.openjdk.tools.javac.util.Options;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This is google-java-format, a new Java formatter that follows the Google Java Style Guide quite
 * precisely---to the letter and to the spirit.
 *
 * <p>This formatter uses the javac parser to generate an AST. Because the AST loses information
 * about the non-tokens in the input (including newlines, comments, etc.), and even some tokens
 * (e.g., optional commas or semicolons), this formatter lexes the input again and follows along in
 * the resulting list of tokens. Its lexer splits all multi-character operators (like "&gt;&gt;")
 * into multiple single-character operators. Each non-token is assigned to a token---non-tokens
 * following a token on the same line go with that token; those following go with the next token---
 * and there is a final EOF token to hold final comments.
 *
 * <p>The formatter walks the AST to generate a Greg Nelson/Derek Oppen-style list of formatting
 * {@link Op}s [1--2] that then generates a structured {@link Doc}. Each AST node type has a visitor
 * to emit a sequence of {@link Op}s for the node.
 *
 * <p>Some data-structure operations are easier in the list of {@link Op}s, while others become
 * easier in the {@link Doc}. The {@link Op}s are walked to attach the comments. As the {@link Op}s
 * are generated, missing input tokens are inserted and incorrect output tokens are dropped,
 * ensuring that the output matches the input even in the face of formatter errors. Finally, the
 * formatter walks the {@link Doc} to format it in the given width.
 *
 * <p>This formatter also produces data structures of which tokens and comments appear where on the
 * input, and on the output, to help output a partial reformatting of a slightly edited input.
 *
 * <p>Instances of the formatter are immutable and thread-safe.
 *
 * <p>[1] Nelson, Greg, and John DeTreville. Personal communication.
 *
 * <p>[2] Oppen, Derek C. "Prettyprinting". ACM Transactions on Programming Languages and Systems,
 * Volume 2 Issue 4, Oct. 1980, pp. 465–483.
 */
@Immutable
public final class Formatter {
  /**
   * This ExecutorService will be used at method {@link Formatter#formatSourceFile(String, boolean, boolean, long, TimeUnit, boolean)}
   * if the method's param {@code useExecutor} is true, then the formatter will submit the task to this Executor.
   * and the default timeout is 1 sec for each file.
   * */
  private static final ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(
          Runtime.getRuntime().availableProcessors(),
          Math.min(Runtime.getRuntime().availableProcessors() * 2, 50),
          60,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<>()
          );

  static final Range<Integer> EMPTY_RANGE = Range.closedOpen(-1, -1);

  private final JavaFormatterOptions options;

  /** A new Formatter instance with default options. */
  public Formatter() {
    this(JavaFormatterOptions.defaultOptions());
  }

  public Formatter(JavaFormatterOptions options) {
    this.options = options;
  }

  /**
   * Construct a {@code Formatter} given a Java compilation unit. Parses the code; builds a {@link
   * JavaInput} and the corresponding {@link JavaOutput}.
   *
   * @param javaInput the input, a Java compilation unit
   * @param javaOutput the {@link JavaOutput}
   * @param options the {@link JavaFormatterOptions}
   */
  static void format(final JavaInput javaInput, JavaOutput javaOutput, JavaFormatterOptions options)
      throws FormatterException {
    Context context = new Context();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    context.put(DiagnosticListener.class, diagnostics);
    Options.instance(context).put("allowStringFolding", "false");
    // TODO(cushon): this should default to the latest supported source level, remove this after
    // backing out
    // https://github.com/google/error-prone-javac/commit/c97f34ddd2308302587ce2de6d0c984836ea5b9f
    Options.instance(context).put(Option.SOURCE, "9");
    JCCompilationUnit unit;
    JavacFileManager fileManager = new JavacFileManager(context, true, UTF_8);
    try {
      fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, ImmutableList.of());
    } catch (IOException e) {
      // impossible
      throw new IOError(e);
    }
    SimpleJavaFileObject source =
        new SimpleJavaFileObject(URI.create("source"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return javaInput.getText();
          }
        };
    Log.instance(context).useSource(source);
    ParserFactory parserFactory = ParserFactory.instance(context);
    JavacParser parser =
        parserFactory.newParser(
            javaInput.getText(),
            /*keepDocComments=*/ true,
            /*keepEndPos=*/ true,
            /*keepLineMap=*/ true);
    unit = parser.parseCompilationUnit();
    unit.sourcefile = source;

    javaInput.setCompilationUnit(unit);
    Iterable<Diagnostic<? extends JavaFileObject>> errorDiagnostics =
        Iterables.filter(diagnostics.getDiagnostics(), Formatter::errorDiagnostic);
    if (!Iterables.isEmpty(errorDiagnostics)) {
      throw FormatterException.fromJavacDiagnostics(errorDiagnostics);
    }
    OpsBuilder builder = new OpsBuilder(javaInput, javaOutput);
    // Output the compilation unit.
    new JavaInputAstVisitor(builder, options.indentationMultiplier()).scan(unit, null);
    builder.sync(javaInput.getText().length());
    builder.drain();
    Doc doc = new DocBuilder().withOps(builder.build()).build();
    doc.computeBreaks(
        javaOutput.getCommentsHelper(), options.maxLineLength(), new Doc.State(+0, 0));
    doc.write(javaOutput);
    javaOutput.flush();
  }

  static boolean errorDiagnostic(Diagnostic<?> input) {
    if (input.getKind() != Diagnostic.Kind.ERROR) {
      return false;
    }
    switch (input.getCode()) {
      case "compiler.err.invalid.meth.decl.ret.type.req":
        // accept constructor-like method declarations that don't match the name of their
        // enclosing class
        return false;
      default:
        break;
    }
    return true;
  }

  /**
   * Format the given input (a Java compilation unit) into the output stream.
   *
   * @throws FormatterException if the input cannot be parsed
   */
  public void formatSource(CharSource input, CharSink output)
      throws FormatterException, IOException {
    // TODO(cushon): proper support for streaming input/output. Input may
    // not be feasible (parsing) but output should be easier.
    output.write(formatSource(input.read()));
  }

  /**
   * Format an input file (or dir), this method will judge the input {@code file}, if it is
   * A directory, then this method will scan the directory to find all of the java source files.
   * Then format every java file. if the {@code useExecutor} is true, then this method will use
   * The ExecuteService {@link Formatter#EXECUTOR_SERVICE} to submit a {@link FormatFileCallable}.
   * You should check the result list's element. if the element is empty, you should do not overwrite
   * it to source file path.
   * if you want to replace the old source by the formatted source, let the param {@code reWrite} as true
   *
   * @param reWrite  whether to reWrite the source file.
   * @param file the input
   * @param useExecutor whether multi-thread mode
   * @param timeout the timeout value, for each java file.
   * @param unit the time unit
   * @param timeoutForAll if true, total timeout is {@code timeout}, else timeout is {@code timeout * fileCount}
   * @return the formatted java source
   */
  public List<String> formatSourceFile(String file, boolean useExecutor, ExecutorService executorService,
                                       boolean reWrite, long timeout, TimeUnit unit, boolean timeoutForAll)
          throws IOException, FormatterException {
    if (Strings.isNullOrEmpty(file)) {
      return Collections.emptyList();
    }
    // re-set the timeout value.
    if (timeout <= 0) {
      timeout = 1;
      unit = TimeUnit.SECONDS;
      timeoutForAll = false;
    }
    if (executorService == null) {
      executorService = EXECUTOR_SERVICE;
    }
    File sourceFile = new File(file);
    if (sourceFile.isFile()) {
      // this is a file
      if (useExecutor) {
        CompletableFuture<List<String>> formattedFuture = CompletableFuture.supplyAsync(() -> {
          Path path = Paths.get(file);
          String formatted;
          try {
            formatted = formatSource(new String(Files.readAllBytes(path), UTF_8));
            writeFile(file, formatted, reWrite);
          } catch (FormatterException | IOException e) {
            formatted = "";
          }
          return ImmutableList.of(formatted); // do not update the file source.
        }, executorService);
        try {
          if (formattedFuture.get(timeout, unit) == null) {
            return Collections.emptyList();
          } else {
            return formattedFuture.getNow(Collections.emptyList());
          }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
          return Collections.emptyList();
        }
      } else {
        Path path = Paths.get(file);
        String formatted = formatSource(new String(Files.readAllBytes(path), UTF_8));
        writeFile(file, formatted, reWrite);
        return ImmutableList.of(formatted); // do not update the file source.
      }
    } else if (sourceFile.isDirectory()) {
      // this is a directory
      List<String> filePathLit = Lists.newArrayList();
      scanJavaFileInDirectory(file, filePathLit);
      if (filePathLit.isEmpty()) {
        return Collections.emptyList();
      }
      if (useExecutor) {
        List<CompletableFuture<String>> formattedFutureList = Lists.newArrayList();
        for (String p : filePathLit) {
          formattedFutureList.add(CompletableFuture.supplyAsync(() -> {
            Path path = Paths.get(p);
            String formatted;
            try {
              formatted = formatSource(new String(Files.readAllBytes(path), UTF_8));
              writeFile(p, formatted, reWrite);
            } catch (FormatterException | IOException e) {
              formatted = "";
            }
            return formatted;
          }, executorService));
        }
        // check the status.
        CompletableFuture<List<String>> formattedResultFuture =
                CompletableFuture
                .allOf(formattedFutureList.toArray(new CompletableFuture[formattedFutureList.size()]))
                .thenApply(formattedFuture ->
                        formattedFutureList.stream().map(CompletableFuture::join).collect(Collectors.toList()));
        try {
          long timeOutValue = timeout;
          if (!timeoutForAll) {
            timeOutValue = formattedFutureList.size() * timeout;
          }
          if (formattedResultFuture.get(timeOutValue, unit) == null) {
            return Collections.emptyList();
          } else {
            return formattedResultFuture.getNow(Collections.emptyList());
          }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
          return Collections.emptyList();
        }
      } else {
        List<String> formattedList = Lists.newArrayList();
        for (String p : filePathLit) {
          Path path = Paths.get(p);
          String formatted = formatSource(new String(Files.readAllBytes(path), UTF_8));
          formattedList.add(formatted);
          writeFile(p, formatted, reWrite);
        }
        return formattedList;
      }
    } else {
      // doNothing.
      return Collections.emptyList();
    }
  }

  /**
   *  Re-write file with the new content.
   *
   * @param filePath the file path
   * @param content the new content
   * @param reWrite whether to re-write
   * @throws FileNotFoundException Not Find
   */
  private void writeFile(String filePath, String content, boolean reWrite)
          throws FileNotFoundException {
    if (!Strings.isNullOrEmpty(content) && reWrite) {
      // re-write the formatted source.
      PrintWriter printWriter = new PrintWriter(new File(filePath));
      printWriter.print(content);
      printWriter.flush();
      printWriter.close();
    }
  }

  /**
   *  Scan the directory {@code directory} to find all of the java files.
   *
   * @param directory the scan directory
   * @param filePathList the result.
   */
  private void scanJavaFileInDirectory(String directory, List<String> filePathList) {
    if (Strings.isNullOrEmpty(directory)) {
      return;
    }
    File sourceFile = new File(directory);
    if (sourceFile.isFile()) {
      // this is a file
      filePathList.add(directory);
      return;
    }
    // this is a directory.
    File[] fileList = sourceFile.listFiles();
    if (fileList == null) {
      return; // no file
    }
    for (File file : fileList) {
      if (file.isFile() && file.getAbsolutePath().endsWith(".java")) {
        filePathList.add(file.getAbsolutePath());
      } else if (file.isDirectory()) {
        scanJavaFileInDirectory(file.getAbsolutePath(), filePathList);
      }
    }
  }

  /**
   * Format an input string (a Java compilation unit) into an output string.
   *
   * <p>Leaves import statements untouched.
   *
   * @param input the input string
   * @return the output string
   * @throws FormatterException if the input string cannot be parsed
   */
  public String formatSource(String input) throws FormatterException {
    return formatSource(input, ImmutableList.of(Range.closedOpen(0, input.length())));
  }

  /**
   * Formats an input string (a Java compilation unit) and fixes imports.
   *
   * <p>Fixing imports includes ordering, spacing, and removal of unused import statements.
   *
   * @param input the input string
   * @return the output string
   * @throws FormatterException if the input string cannot be parsed
   * @see <a
   *     href="https://google.github.io/styleguide/javaguide.html#s3.3.3-import-ordering-and-spacing">
   *     Google Java Style Guide - 3.3.3 Import ordering and spacing</a>
   */
  public String formatSourceAndFixImports(String input) throws FormatterException {
    input = ImportOrderer.reorderImports(input);
    input = RemoveUnusedImports.removeUnusedImports(input);
    return formatSource(input);
  }

  /**
   * Format an input string (a Java compilation unit), for only the specified character ranges.
   * These ranges are extended as necessary (e.g., to encompass whole lines).
   *
   * @param input the input string
   * @param characterRanges the character ranges to be reformatted
   * @return the output string
   * @throws FormatterException if the input string cannot be parsed
   */
  public String formatSource(String input, Collection<Range<Integer>> characterRanges)
      throws FormatterException {
    return JavaOutput.applyReplacements(input, getFormatReplacements(input, characterRanges));
  }

  /**
   * Emit a list of {@link Replacement}s to convert from input to output.
   *
   * @param input the input compilation unit
   * @param characterRanges the character ranges to reformat
   * @return a list of {@link Replacement}s, sorted from low index to high index, without overlaps
   * @throws FormatterException if the input string cannot be parsed
   */
  public ImmutableList<Replacement> getFormatReplacements(
      String input, Collection<Range<Integer>> characterRanges) throws FormatterException {
    JavaInput javaInput = new JavaInput(input);

    // TODO(cushon): this is only safe because the modifier ordering doesn't affect whitespace,
    // and doesn't change the replacements that are output. This is not true in general for
    // 'de-linting' changes (e.g. import ordering).
    javaInput = ModifierOrderer.reorderModifiers(javaInput, characterRanges);

    String lineSeparator = Newlines.guessLineSeparator(input);
    JavaOutput javaOutput =
        new JavaOutput(lineSeparator, javaInput, new JavaCommentsHelper(lineSeparator, options));
    try {
      format(javaInput, javaOutput, options);
    } catch (FormattingError e) {
      throw new FormatterException(e.diagnostics());
    }
    RangeSet<Integer> tokenRangeSet = javaInput.characterRangesToTokenRanges(characterRanges);
    return javaOutput.getFormatReplacements(tokenRangeSet);
  }

  /**
   * Converts zero-indexed, [closed, open) line ranges in the given source file to character ranges.
   */
  public static RangeSet<Integer> lineRangesToCharRanges(
      String input, RangeSet<Integer> lineRanges) {
    List<Integer> lines = new ArrayList<>();
    Iterators.addAll(lines, Newlines.lineOffsetIterator(input));
    lines.add(input.length() + 1);

    final RangeSet<Integer> characterRanges = TreeRangeSet.create();
    for (Range<Integer> lineRange :
        lineRanges.subRangeSet(Range.closedOpen(0, lines.size() - 1)).asRanges()) {
      int lineStart = lines.get(lineRange.lowerEndpoint());
      // Exclude the trailing newline. This isn't strictly necessary, but handling blank lines
      // as empty ranges is convenient.
      int lineEnd = lines.get(lineRange.upperEndpoint()) - 1;
      Range<Integer> range = Range.closedOpen(lineStart, lineEnd);
      characterRanges.add(range);
    }
    return characterRanges;
  }
}
