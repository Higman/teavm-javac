/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.teavm.javac;

import com.sun.tools.javac.api.JavacTool;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.teavm.backend.javascript.JavaScriptTarget;
import org.teavm.classlib.impl.JCLPlugin;
import org.teavm.diagnostics.Problem;
import org.teavm.diagnostics.ProblemSeverity;
import org.teavm.interop.Async;
import org.teavm.jso.JSBody;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLInputElement;
import org.teavm.jso.dom.html.HTMLLinkElement;
import org.teavm.jso.impl.JSOPlugin;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;
import org.teavm.model.ClassHolderSource;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.parsing.CompositeClassHolderSource;
import org.teavm.parsing.DirectoryClasspathClassHolderSource;
import org.teavm.platform.async.AsyncCallback;
import org.teavm.platform.plugin.PlatformPlugin;
import org.teavm.vm.TeaVM;
import org.teavm.vm.TeaVMBuilder;

public final class Client {
    private Client() {
    }

    public static void main(String[] args) throws IOException {
        createStdlib();
        loadTeaVMClasslib();

        HTMLDocument document = Window.current().getDocument();
        HTMLInputElement textArea = (HTMLInputElement) document.getElementById("source-code");
        document.getElementById("compile-button").addEventListener("click", event -> {
            try {
                createSourceFile(textArea.getValue());
                if (doCompile() && generateJavaScript()) {
                    fetchResult();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private static void fetchResult() throws IOException {
        File file = new File("/out/Hello.class");
        Uint8Array data = Uint8Array.create((int) file.length());
        try (FileInputStream input = new FileInputStream(file)) {
            int index = 0;
            while (true) {
                short b = (short) input.read();
                if (b < 0) {
                    break;
                }
                data.set(index++, b);
            }
        }

        HTMLDocument document = Window.current().getDocument();
        HTMLLinkElement element = (HTMLLinkElement) document.createElement("a");
        element.setHref(createObjectURL(data));
        setDownload(element, "Hello.class");
        document.getBody().appendChild(element);
        element.click();
        element.delete();
    }


    @JSBody(params = "data",
            script = "return URL.createObjectURL(new Blob([data], { type: 'application/octet-stream' }));")
    private static native String createObjectURL(Uint8Array data);

    @JSBody(params = { "target", "name" }, script = "target.download = name;")
    private static native void setDownload(HTMLLinkElement target, String name);

    private static boolean doCompile() throws IOException {
        JavaCompiler compiler = JavacTool.create();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(
                Arrays.asList(new File("/Hello.java")));
        OutputStreamWriter out = new OutputStreamWriter(System.out);

        new File("/out").mkdirs();
        JavaCompiler.CompilationTask task = compiler.getTask(out, fileManager, null,
                Arrays.asList("-verbose", "-d", "/out"), null, compilationUnits);
        boolean result = task.call();
        out.flush();

        return result;
    }

    private static boolean generateJavaScript() {
        Properties stdlibMapping = new Properties();
        stdlibMapping.setProperty("packagePrefix.java", "org.teavm.classlib");
        stdlibMapping.setProperty("classPrefix.java", "T");

        List<ClassHolderSource> classSources = new ArrayList<>();
        classSources.add(new DirectoryClasspathClassHolderSource(new File("/out")));
        classSources.add(new DirectoryClasspathClassHolderSource(new File("/teavm-stdlib"), stdlibMapping));

        TeaVM teavm = new TeaVMBuilder(new JavaScriptTarget())
                .setClassSource(new CompositeClassHolderSource(classSources))
                .build();
        new JSOPlugin().install(teavm);
        new PlatformPlugin().install(teavm);
        new JCLPlugin().install(teavm);

        teavm.entryPoint("main", new MethodReference("Hello", "main", ValueType.parse(String[].class),
                ValueType.VOID))
                .withArrayValue(1, "java.lang.String");
        File outDir = new File("/js-out");
        outDir.mkdirs();
        teavm.build(outDir, "classes.js");
        boolean hasSevere = false;
        for (Problem problem : teavm.getProblemProvider().getProblems()) {
            if (problem.getSeverity() == ProblemSeverity.ERROR) {
                hasSevere = true;
            }
        }
        TeaVMProblemRenderer.describeProblems(teavm);

        return !hasSevere;
    }

    private static void createStdlib() throws IOException {
        System.setProperty("sun.boot.class.path", "/stdlib");

        File baseDir = new File("/stdlib");
        baseDir.mkdirs();
        ClassLoader loader = ClassLoader.getSystemClassLoader();

        try (ZipInputStream input = new ZipInputStream(loader.getResourceAsStream("stdlib/data.zip"))) {
            unzip(input, baseDir);
        }
    }

    private static void loadTeaVMClasslib() throws IOException {
        File baseDir = new File("/teavm-stdlib");
        baseDir.mkdirs();

        byte[] data = downloadFile("teavm-classlib.zip");
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(data))) {
            unzip(input, baseDir);
        }
    }

    private static void unzip(ZipInputStream input, File toDir) throws IOException {
        while (true) {
            ZipEntry entry = input.getNextEntry();
            if (entry == null) {
                break;
            }
            if (entry.isDirectory()) {
                continue;
            }

            File outputFile = new File(toDir, entry.getName());
            outputFile.getParentFile().mkdirs();
            try (OutputStream output = new FileOutputStream(outputFile)) {
                copy(input, output);
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[1024];
        while (true) {
            int bytesRead = input.read(buffer);
            if (bytesRead < 0) {
                break;
            }
            output.write(buffer, 0, bytesRead);
        }
    }

    @Async
    private static native byte[] downloadFile(String url);

    private static void downloadFile(String url, AsyncCallback<byte[]> callback) {
        XMLHttpRequest xhr = XMLHttpRequest.create();
        xhr.open("GET", url, true);
        xhr.setResponseType("arraybuffer");
        xhr.onComplete(() -> {
            ArrayBuffer buffer = (ArrayBuffer) xhr.getResponse();
            Int8Array jsArray = Int8Array.create(buffer);
            byte[] array = new byte[jsArray.getLength()];
            for (int i = 0; i < array.length; ++i) {
                array[i] = jsArray.get(i);
            }
            callback.complete(array);
        });
        xhr.send();
    }

    private static void createSourceFile(String content) throws IOException {
        File file = new File("/Hello.java");

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
            writer.write(content);
        }
    }
}
