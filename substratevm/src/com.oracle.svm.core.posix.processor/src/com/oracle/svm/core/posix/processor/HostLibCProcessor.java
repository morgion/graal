/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, BELLSOFT. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.posix.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Calendar;
import java.util.Set;

@SupportedAnnotationTypes("com.oracle.svm.core.posix.linux.libc.GenerateHostLibC")
public class HostLibCProcessor extends AbstractProcessor {

    private static final String CLASS_NAME = "HostLibCImpl";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        try {
            String libc = detectLibc();

            for (TypeElement annot: annotations) {
                for (Element element: roundEnvironment.getElementsAnnotatedWith(annot)) {
                    PackageElement packageElement = (PackageElement) element.getEnclosingElement();
                    String packageName = packageElement.getQualifiedName().toString();
                    String qualifiedName = packageName + '.' + CLASS_NAME;

                    final JavaFileObject output = processingEnv.getFiler().createSourceFile(qualifiedName, element);
                    try (PrintStream stream = new PrintStream(output.openOutputStream(), true, "UTF-8")) {
                        stream.println("/*\n" +
                                " * Copyright (c) " + Calendar.getInstance().get(Calendar.YEAR) +
                                " Oracle and/or its affiliates. All rights reserved. This\n" +
                                " * code is released under a tri EPL/GPL/LGPL license. You can use it,\n" +
                                " * redistribute it and/or modify it under the terms of the:\n" +
                                " *\n" +
                                " * Eclipse Public License version 2.0, or\n" +
                                " * GNU General Public License version 2, or\n" +
                                " * GNU Lesser General Public License version 2.1.\n" +
                                " */");
                        stream.println();
                        stream.println("// GENERATED BY " + getClass().getName());
                        stream.println();
                        stream.println("package " + packageName + ";");
                        stream.println();
                        stream.println("import com.oracle.svm.core.c.libc.*;");
                        stream.println();
                        stream.println("public class " + CLASS_NAME + " extends HostLibC {");
                        stream.println();
                        stream.println("    @Override public LibCBase create() {");
                        stream.println("        return new " + libc + "();");
                        stream.println("    }");
                        stream.println("}");
                    }
                }
            }
        } catch (IOException e) {
            abort(e.getClass() + " " + e.getMessage());
        }
        return true;
    }

    private static String detectLibc() throws IOException {
        if (!System.getProperty("os.name").startsWith("Linux")) {
            return "NoLibC";
        }

        String javaBinary = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process ldd = new ProcessBuilder("ldd", javaBinary).start();
        String line;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(ldd.getInputStream()))) {
            while ((line = reader.readLine()) != null) {
                if (line.contains("musl")) {
                    return "MuslLibC";
                }
            }
        }
        return "GLibC";
    }

    private String abort(String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
        throw new IllegalStateException(message);
    }
}

