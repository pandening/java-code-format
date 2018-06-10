package com.google.googlejavaformat;

import java.util.Objects;

/**
 * Created on 2018/6/7 08:25.
 *
 * @author <a href="mailto:hujian06@meituan.com"> HuJian </a>
 */
public class FormatterTest {

    public static void main(String[] args) {
        String a = new String("a");
        String b = new String("a");

        Integer ia = 1;
        Integer ib = 1;

        System.out.println(Objects.equals(ia, ib));

    }

}
