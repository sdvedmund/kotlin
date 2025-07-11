/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test;

import com.google.common.collect.Lists;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.TestDataFile;
import com.intellij.util.lang.JavaVersion;
import junit.framework.TestCase;
import kotlin.Unit;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.analyzer.AnalysisResult;
import org.jetbrains.kotlin.builtins.DefaultBuiltIns;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.checkers.CompilerTestLanguageVersionSettingsKt;
import org.jetbrains.kotlin.cli.common.config.ContentRootsKt;
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity;
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl;
import org.jetbrains.kotlin.jvm.compiler.LoadDescriptorUtil;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.resolve.lazy.JvmResolveUtil;
import org.jetbrains.kotlin.storage.LockBasedStorageManager;
import org.jetbrains.kotlin.test.testFramework.KtUsefulTestCase;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.util.StringUtilsKt;
import org.jetbrains.kotlin.utils.ExceptionUtilsKt;
import org.junit.Assert;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.FileInfo;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.jetbrains.kotlin.test.InTextDirectivesUtils.*;

public class KotlinTestUtils {
    public static final String ACTUAL_DATA_DIFFERS_FROM_FILE_CONTENT = "Actual data differs from file content";
    public static String TEST_MODULE_NAME = "test-module";

    private static final boolean RUN_IGNORED_TESTS_AS_REGULAR =
            Boolean.getBoolean("org.jetbrains.kotlin.run.ignored.tests.as.regular");

    private static final boolean PRINT_STACKTRACE_FOR_IGNORED_TESTS =
            Boolean.getBoolean("org.jetbrains.kotlin.print.stacktrace.for.ignored.tests");

    private static final boolean DONT_IGNORE_TESTS_WORKING_ON_COMPATIBLE_BACKEND =
            Boolean.getBoolean("org.jetbrains.kotlin.dont.ignore.tests.working.on.compatible.backend");

    private static final boolean AUTOMATICALLY_UNMUTE_PASSED_TESTS = false;
    private static final boolean AUTOMATICALLY_MUTE_FAILED_TESTS = false;

    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile("^//\\s*([A-Z_0-9]+)(:[ \\t]*(.*))?$", Pattern.MULTILINE);

    private KotlinTestUtils() {
    }

    @NotNull
    public static AnalysisResult analyzeFile(@NotNull KtFile file, @NotNull KotlinCoreEnvironment environment) {
        return JvmResolveUtil.analyze(file, environment);
    }

    @NotNull
    public static KotlinCoreEnvironment createEnvironmentWithMockJdkAndIdeaAnnotations(Disposable disposable) {
        return createEnvironmentWithMockJdkAndIdeaAnnotations(disposable, ConfigurationKind.ALL);
    }

    @NotNull
    public static KotlinCoreEnvironment createEnvironmentWithMockJdkAndIdeaAnnotations(Disposable disposable, @NotNull ConfigurationKind configurationKind) {
        return createEnvironmentWithJdkAndNullabilityAnnotationsFromIdea(disposable, configurationKind, TestJdkKind.MOCK_JDK);
    }

    @NotNull
    public static KotlinCoreEnvironment createEnvironmentWithJdkAndNullabilityAnnotationsFromIdea(
            @NotNull Disposable disposable,
            @NotNull ConfigurationKind configurationKind,
            @NotNull TestJdkKind jdkKind
    ) {
        return KotlinCoreEnvironment.createForTests(
                disposable, newConfiguration(configurationKind, jdkKind, KtTestUtil.getAnnotationsJar()), EnvironmentConfigFiles.JVM_CONFIG_FILES
        );
    }

    @NotNull
    public static KotlinCoreEnvironment createEnvironmentWithFullJdkAndIdeaAnnotations(Disposable disposable) {
        return createEnvironmentWithJdkAndNullabilityAnnotationsFromIdea(disposable, ConfigurationKind.ALL, TestJdkKind.FULL_JDK);
    }

    @NotNull
    public static File tmpDirForTest(TestCase test) throws IOException {
        return KtTestUtil.tmpDirForTest(test.getClass().getSimpleName(), test.getName());
    }

    @NotNull
    public static CompilerConfiguration newConfiguration() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(CommonConfigurationKeys.MODULE_NAME, TEST_MODULE_NAME);

        configuration.put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, new MessageCollector() {
            @Override
            public void clear() {
            }

            @Override
            public void report(
                    @NotNull CompilerMessageSeverity severity, @NotNull String message, @Nullable CompilerMessageSourceLocation location
            ) {
                if (severity == CompilerMessageSeverity.ERROR) {
                    String prefix = location == null
                                  ? ""
                                  : "(" + location.getPath() + ":" + location.getLine() + ":" + location.getColumn() + ") ";
                    throw new AssertionError(prefix + message);
                }
            }

            @Override
            public boolean hasErrors() {
                return false;
            }
        });

        return configuration;
    }

    @NotNull
    public static CompilerConfiguration newConfiguration(
            @NotNull ConfigurationKind configurationKind,
            @NotNull TestJdkKind jdkKind,
            @NotNull File... extraClasspath
    ) {
        return newConfiguration(configurationKind, jdkKind, Arrays.asList(extraClasspath), Collections.emptyList());
    }

    @NotNull
    public static CompilerConfiguration newConfiguration(
            @NotNull ConfigurationKind configurationKind,
            @NotNull TestJdkKind jdkKind,
            @NotNull List<File> classpath,
            @NotNull List<File> javaSource
    ) {
        CompilerConfiguration configuration = newConfiguration();
        JvmContentRootsKt.addJavaSourceRoots(configuration, javaSource);
        if (jdkKind == TestJdkKind.MOCK_JDK) {
            JvmContentRootsKt.addJvmClasspathRoot(configuration, KtTestUtil.findMockJdkRtJar());
            configuration.put(JVMConfigurationKeys.NO_JDK, true);
        }
        else if (jdkKind == TestJdkKind.MODIFIED_MOCK_JDK) {
            JvmContentRootsKt.addJvmClasspathRoot(configuration, KtTestUtil.findMockJdkRtModified());
            configuration.put(JVMConfigurationKeys.NO_JDK, true);
        }
        else if (jdkKind == TestJdkKind.FULL_JDK_11) {
            configuration.put(JVMConfigurationKeys.JDK_HOME, KtTestUtil.getJdk11Home());
        }
        else if (jdkKind == TestJdkKind.FULL_JDK_17) {
            configuration.put(JVMConfigurationKeys.JDK_HOME, KtTestUtil.getJdk17Home());
        }
        else if (jdkKind == TestJdkKind.FULL_JDK_21) {
            configuration.put(JVMConfigurationKeys.JDK_HOME, KtTestUtil.getJdk21Home());
        }
        else if (JavaVersion.current().compareTo(JavaVersion.compose(9)) >= 0) {
            configuration.put(JVMConfigurationKeys.JDK_HOME, new File(System.getProperty("java.home")));
        }

        if (configurationKind.getWithRuntime()) {
            JvmContentRootsKt.addJvmClasspathRoot(configuration, ForTestCompileRuntime.runtimeJarForTests());
            JvmContentRootsKt.addJvmClasspathRoot(configuration, ForTestCompileRuntime.scriptRuntimeJarForTests());
            JvmContentRootsKt.addJvmClasspathRoot(configuration, ForTestCompileRuntime.kotlinTestJarForTests());
        }
        else if (configurationKind.getWithMockRuntime()) {
            JvmContentRootsKt.addJvmClasspathRoot(configuration, ForTestCompileRuntime.minimalRuntimeJarForTests());
            JvmContentRootsKt.addJvmClasspathRoot(configuration, ForTestCompileRuntime.scriptRuntimeJarForTests());
        }
        if (configurationKind.getWithReflection()) {
            JvmContentRootsKt.addJvmClasspathRoot(configuration, ForTestCompileRuntime.reflectJarForTests());
        }

        JvmContentRootsKt.addJvmClasspathRoots(configuration, classpath);
        JvmContentRootsKt.configureJdkClasspathRoots(configuration);

        return configuration;
    }

    public static void resolveAllKotlinFiles(KotlinCoreEnvironment environment) throws IOException {
        List<KotlinSourceRoot> roots = ContentRootsKt.getKotlinSourceRoots(environment.getConfiguration());
        if (roots.isEmpty()) return;
        List<KtFile> ktFiles = new ArrayList<>();
        for (KotlinSourceRoot root : roots) {
            File file = new File(root.getPath());
            if (file.isFile()) {
                ktFiles.add(loadKtFile(environment.getProject(), file));
            }
            else {
                //noinspection ConstantConditions
                for (File childFile : file.listFiles()) {
                    if (childFile.getName().endsWith(".kt") || childFile.getName().endsWith(".kts")) {
                        ktFiles.add(loadKtFile(environment.getProject(), childFile));
                    }
                }
            }
        }
        JvmResolveUtil.analyze(ktFiles, environment);
    }

    public static void assertEqualsToFile(@NotNull Path expectedFile, @NotNull String actual) {
        assertEqualsToFile(expectedFile.toFile(), actual);
    }

    public static void assertEqualsToFile(@NotNull File expectedFile, @NotNull String actual) {
        assertEqualsToFile(expectedFile, actual, s -> s);
    }

    public static void assertEqualsToFile(@NotNull String message, @NotNull File expectedFile, @NotNull String actual) {
        assertEqualsToFile(message, expectedFile, actual, s -> s);
    }

    public static void assertEqualsToFile(@NotNull File expectedFile, @NotNull String actual, @NotNull Function1<String, String> sanitizer) {
        assertEqualsToFile(ACTUAL_DATA_DIFFERS_FROM_FILE_CONTENT, expectedFile, actual, sanitizer);
    }

    public static void assertValueAgnosticEqualsToFile(File expectedFile, @NotNull String actual) {
        ValueAgnosticSanitizer sanitizer = new ValueAgnosticSanitizer(actual);

        String expectedText = tryLoadExpectedFile(expectedFile, sanitizer::generateExpectedText);
        String expectedSanitizedText = applyDefaultAndCustomSanitizer(expectedText, s -> s);

        String sanitizedActualBasedOnExpectPlaceholders =
                applyDefaultAndCustomSanitizer(
                        sanitizer.generateSanitizedActualTextBasedOnExpectPlaceholders(expectedSanitizedText), s -> s);

        KotlinTestUtils.FileComparisonResult comparisonResult = new KotlinTestUtils.FileComparisonResult(
                expectedFile,
                expectedText,
                expectedSanitizedText,
                sanitizedActualBasedOnExpectPlaceholders
        );

        failIfNotEqual(ACTUAL_DATA_DIFFERS_FROM_FILE_CONTENT, comparisonResult);
    }

    public static FileComparisonResult compareExpectFileWithActualText(@NotNull File expectedFile, @NotNull String actual, @NotNull Function1<String, String> sanitizer) {
        Function0<String> getActualSanitizedText = () -> applyDefaultAndCustomSanitizer(actual, sanitizer);

        String expectedText = tryLoadExpectedFile(expectedFile, getActualSanitizedText);
        String expectedSanitizedText = applyDefaultAndCustomSanitizer(expectedText, sanitizer);

        return new FileComparisonResult(expectedFile, expectedText, expectedSanitizedText, getActualSanitizedText.invoke());
    }

    public static String tryLoadExpectedFile(@NotNull File expectedFile, @NotNull Function0<String> getSanitizedActualText) {
        try {
            if (!expectedFile.exists()) {
                if (KtUsefulTestCase.IS_UNDER_TEAMCITY) {
                    Assert.fail("Expected data file " + expectedFile + " did not exist");
                } else {
                    FileUtil.writeToFile(expectedFile, getSanitizedActualText.invoke());
                    Assert.fail("Expected data file did not exist. Generating: " + expectedFile);
                }
            }
            return FileUtil.loadFile(expectedFile, CharsetToolkit.UTF8, true);
        }
        catch (IOException e) {
            throw ExceptionUtilsKt.rethrow(e);
        }
    }

    public static class FileComparisonResult {
        public final @NotNull File expectedFile;
        public final @NotNull String expectedText;
        public final @NotNull String expectedSanitizedText;
        public final @NotNull String actualSanitizedText;
        public final boolean doesEqual;

        public FileComparisonResult(
                @NotNull File expectedFile,
                @NotNull String expectedText,
                @NotNull String expectedSanitizedText,
                @NotNull String actualSanitizedText
        ) {
            this.expectedFile = expectedFile;
            this.expectedText = expectedText;
            this.expectedSanitizedText = expectedSanitizedText;
            this.actualSanitizedText = actualSanitizedText;
            this.doesEqual = Objects.equals(expectedSanitizedText, actualSanitizedText);
        }
    }

    public static String applyDefaultAndCustomSanitizer(String text, @NotNull Function1<String, String> sanitizer) {
        String textAfterDefaultSanitizer = StringUtilsKt.trimTrailingWhitespacesAndAddNewlineAtEOF(StringUtil.convertLineSeparators(text.trim()));
        return sanitizer.invoke(textAfterDefaultSanitizer);
    }

    public static void assertEqualsToFile(@NotNull String message, @NotNull File expectedFile, @NotNull String actual, @NotNull Function1<String, String> sanitizer) {
        failIfNotEqual(message, compareExpectFileWithActualText(expectedFile, actual, sanitizer));
    }

    public static void failIfNotEqual(@NotNull String message, FileComparisonResult fileComparisonResult) {
        if (!fileComparisonResult.doesEqual) {
            throw new AssertionFailedError(
                    message + ": " + fileComparisonResult.expectedFile.getName(),
                    new FileInfo(fileComparisonResult.expectedFile.getAbsolutePath(), fileComparisonResult.expectedText.getBytes(StandardCharsets.UTF_8)),
                    fileComparisonResult.actualSanitizedText
            );
        }
    }

    public static JavaCompilationResult compileKotlinWithJava(
            @NotNull List<File> javaFiles,
            @NotNull List<File> ktFiles,
            @NotNull File outDir,
            @NotNull Disposable disposable,
            @Nullable Function1<CompilerConfiguration, Unit> updateConfiguration
    ) {
        if (!ktFiles.isEmpty()) {
            KotlinCoreEnvironment environment = createEnvironmentWithFullJdkAndIdeaAnnotations(disposable);
            CompilerTestLanguageVersionSettingsKt.setupLanguageVersionSettingsForMultifileCompilerTests(ktFiles, environment);
            if (updateConfiguration != null) {
                updateConfiguration.invoke(environment.getConfiguration());
            }
            LoadDescriptorUtil.compileKotlinToDirAndGetModule(ktFiles, outDir, environment);
        }
        else {
            boolean mkdirs = outDir.mkdirs();
            assert mkdirs : "Not created: " + outDir;
        }
        if (javaFiles.isEmpty()) return JavaCompilationResult.Success.INSTANCE;

        List<String> options = Arrays.asList(
                "-classpath", outDir.getPath() + File.pathSeparator + ForTestCompileRuntime.runtimeJarForTests(),
                "-d", outDir.getPath()
        );
        return JvmCompilationUtils.compileJavaFiles(javaFiles, options);
    }

    @NotNull
    public static Directives parseDirectives(String expectedText) {
        return parseDirectives(expectedText, new Directives());
    }

    @NotNull
    public static Directives parseDirectives(String expectedText, @NotNull Directives directives) {
        Matcher directiveMatcher = DIRECTIVE_PATTERN.matcher(expectedText);
        while (directiveMatcher.find()) {
            String name = directiveMatcher.group(1);
            String value = directiveMatcher.group(3);
            directives.put(name, value);
        }
        return directives;
    }

    public static List<String> loadBeforeAfterText(String filePath) {
        String content;

        try {
            content = FileUtil.loadFile(new File(filePath), true);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

        List<String> files = TestFiles.createTestFiles("", content, new TestFiles.TestFileFactoryNoModules<String>() {
            @NotNull
            @Override
            public String create(@NotNull String fileName, @NotNull String text, @NotNull Directives directives) {
                int firstLineEnd = text.indexOf('\n');
                return StringUtil.trimTrailing(text.substring(firstLineEnd + 1));
            }
        });

        Assert.assertTrue("Exactly two files expected: ", files.size() == 2);

        return files;
    }

    public enum CommentType {
        ALL,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    @NotNull
    public static String getLastCommentInFile(@NotNull KtFile file) {
        return CollectionsKt.first(getLastCommentsInFile(file, CommentType.ALL, true));
    }

    @NotNull
    public static List<String> getLastCommentsInFile(@NotNull KtFile file, CommentType commentType, boolean assertMustExist) {
        PsiElement lastChild = file.getLastChild();
        if (lastChild != null && lastChild.getNode().getElementType().equals(KtTokens.WHITE_SPACE)) {
            lastChild = lastChild.getPrevSibling();
        }
        assert lastChild != null;

        List<String> comments = new ArrayList<>();

        while (true) {
            if (lastChild.getNode().getElementType().equals(KtTokens.BLOCK_COMMENT)) {
                if (commentType == CommentType.ALL || commentType == CommentType.BLOCK_COMMENT) {
                    String lastChildText = lastChild.getText();
                    comments.add(lastChildText.substring(2, lastChildText.length() - 2).trim());
                }
            }
            else if (lastChild.getNode().getElementType().equals(KtTokens.EOL_COMMENT)) {
                if (commentType == CommentType.ALL || commentType == CommentType.LINE_COMMENT) {
                    comments.add(lastChild.getText().substring(2).trim());
                }
            }
            else {
                break;
            }

            lastChild = lastChild.getPrevSibling();
        }

        if (comments.isEmpty() && assertMustExist) {
            throw new AssertionError(String.format(
                    "Test file '%s' should end in a comment of type %s; last node was: %s", file.getName(), commentType, lastChild));
        }

        return comments;
    }

    public static String navigationMetadata(@TestDataFile String testFile) {
        return testFile;
    }

    public interface DoTest {
        void invoke(@NotNull String filePath);
    }

    public static void runTest(@NotNull DoTest test, @NotNull TestCase testCase, @TestDataFile String testDataFile) {
        runTestImpl(testWithCustomIgnoreDirective(test, TargetBackend.ANY, IGNORE_BACKEND_DIRECTIVE_PREFIXES), testCase, ForTestCompileRuntime.transformTestDataPath(testDataFile).getPath());
    }

    public static void runTest(@NotNull TestCase testCase, @NotNull Function0<Unit> test) {
        MuteWithDatabaseJunit4Kt.runTest(testCase, test);
    }

    public static void runTestWithThrowable(@NotNull TestCase testCase, @NotNull RunnableWithThrowable test) {
        MuteWithDatabaseJunit4Kt.runTest(testCase, () -> {
            try {
                test.run();
            }
            catch (Throwable throwable) {
                throw new IllegalStateException(throwable);
            }
            return null;
        });
    }

    // In this test runner version the `testDataFile` parameter is annotated by `TestDataFile`.
    // So only file paths passed to this parameter will be used in navigation actions, like "Navigate to testdata" and "Related Symbol..."
    public static void runTest(DoTest test, TargetBackend targetBackend, @TestDataFile String testDataFile) {
        runTest0(test, targetBackend, ForTestCompileRuntime.transformTestDataPath(testDataFile).getPath());
    }

    public static void runTestWithCustomIgnoreDirective(DoTest test, TargetBackend targetBackend, @TestDataFile String testDataFile, String ignoreDirective) {
        runTestImpl(testWithCustomIgnoreDirective(test, targetBackend, ignoreDirective), null, ForTestCompileRuntime.transformTestDataPath(testDataFile).getPath());
    }

    // In this test runner version, NONE of the parameters are annotated by `TestDataFile`.
    // So DevKit will use test name to determine related files in navigation actions, like "Navigate to testdata" and "Related Symbol..."
    //
    // Pro:
    // * in most cases, it shows all related files including generated js files, for example.
    // Cons:
    // * sometimes, for too common/general names, it shows many variants to navigate
    // * it adds an additional step for navigation -- you must choose an exact file to navigate
    public static void runTest0(DoTest test, TargetBackend targetBackend, String testDataFilePath) {
        String[] prefixes = test.getClass().getSimpleName().startsWith("Fir")
                            ? new String[] { IGNORE_BACKEND_DIRECTIVE_PREFIX, IGNORE_BACKEND_K2_DIRECTIVE_PREFIX }
                            : IGNORE_BACKEND_DIRECTIVE_PREFIXES;
        runTestImpl(testWithCustomIgnoreDirective(test, targetBackend, prefixes), null, testDataFilePath);
    }

    private static void runTestImpl(@NotNull DoTest test, @Nullable TestCase testCase, String testDataFilePath) {
        if (testCase != null && !isRunTestOverridden(testCase)) {
            Function0<Unit> wrapWithMuteInDatabase = MuteWithDatabaseJunit4Kt.wrapWithMuteInDatabase(testCase, () -> {
                try {
                    test.invoke(testDataFilePath);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            if (wrapWithMuteInDatabase != null) {
                wrapWithMuteInDatabase.invoke();
                return;
            }
        }
        test.invoke(testDataFilePath);
    }

    private static boolean isRunTestOverridden(TestCase testCase) {
        Class<?> type = testCase.getClass();
        while (type != null) {
            for (Annotation annotation : type.getDeclaredAnnotations()) {
                if (annotation.annotationType().equals(WithMutedInDatabaseRunTest.class)) {
                    return true;
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static DoTest testWithCustomIgnoreDirective(DoTest test, TargetBackend targetBackend, String... ignoreDirectives) {
        return filePath -> {
            File testDataFile = new File(filePath);

            boolean isIgnored = isIgnoredTarget(targetBackend, testDataFile, ignoreDirectives);

            if (DONT_IGNORE_TESTS_WORKING_ON_COMPATIBLE_BACKEND) {
                // Only ignore if it is ignored for both backends
                // Motivation: this backend works => all good, even if compatible backend fails
                // This backend fails, compatible works => need to know
                isIgnored &= isIgnoredTarget(targetBackend.getCompatibleWith(), testDataFile);
            }

            try {
                test.invoke(filePath);
            }
            catch (Throwable e) {
                if (!isIgnored && AUTOMATICALLY_MUTE_FAILED_TESTS) {
                    String text = KtTestUtil.doLoadFile(testDataFile);
                    String directive = ignoreDirectives[0] + targetBackend.name() + "\n";

                    String newText;
                    if (text.startsWith("//")) {
                        StringBuilder prefixBuilder = new StringBuilder();
                        int l = 0;
                        while (text.startsWith("//", l)) {
                            int r = text.indexOf("\n", l) + 1;
                            if (r <= 0) r = text.length();
                            prefixBuilder.append(text.substring(l, r));
                            l = r;
                        }
                        prefixBuilder.append(directive);
                        prefixBuilder.append(text.substring(l));

                        newText = prefixBuilder.toString();
                    } else {
                        newText = directive + text;
                    }

                    if (!newText.equals(text)) {
                        System.err.println("\"" + directive + "\" was added to \"" + testDataFile + "\"");
                        try {
                            FileUtil.writeToFile(testDataFile, newText);
                        } catch (IOException ioException) {
                            throw ExceptionUtilsKt.rethrow(e);
                        }
                    }
                }

                if (RUN_IGNORED_TESTS_AS_REGULAR || !isIgnored) {
                    throw e;
                }

                if (PRINT_STACKTRACE_FOR_IGNORED_TESTS) {
                    e.printStackTrace();
                } else {
                    System.err.println("MUTED TEST with `" + ignoreDirectives[0] + "`");
                }
                return;
            }

            if (isIgnored) {
                StringBuilder directivesToRemove = new StringBuilder();
                if (AUTOMATICALLY_UNMUTE_PASSED_TESTS) {
                    for (String ignoreDirective: ignoreDirectives){
                        String text = KtTestUtil.doLoadFile(testDataFile);
                        String directive = ignoreDirective + targetBackend.name();
                        directivesToRemove.append(directive);
                        directivesToRemove.append(", ");
                        String newText = Pattern.compile("^" + directive + "\n", Pattern.MULTILINE).matcher(text).replaceAll("");
                        if (!newText.equals(text)) {
                            System.err.println("\"" + directive + "\" was removed from \"" + testDataFile + "\"");
                            try {
                                FileUtil.writeToFile(testDataFile, newText);
                            } catch (IOException e) {
                                throw ExceptionUtilsKt.rethrow(e);
                            }
                        }
                    }
                }

                throw new AssertionError(String.format("Looks like this test can be unmuted. Remove \"%s\" directive.", directivesToRemove.toString()));
            }
        };
    }

    /**
     * @return test data file name specified in the metadata of test method
     */
    @Nullable
    public static String getTestDataFileName(@NotNull Class<?> testCaseClass, @NotNull String testName) {
        try {
            Method method = testCaseClass.getDeclaredMethod(testName);
            return KtTestUtil.getMethodMetadata(method);
        }
        catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @NotNull
    public static KtFile loadKtFile(@NotNull Project project, @NotNull File ioFile) throws IOException {
        String text = FileUtil.loadFile(ioFile, true);
        return new KtPsiFactory(project).createPhysicalFile(ioFile.getName(), text);
    }

    @NotNull
    public static List<KtFile> loadToKtFiles(@NotNull KotlinCoreEnvironment environment, @NotNull List<File> files) throws IOException {
        List<KtFile> ktFiles = Lists.newArrayList();
        for (File file : files) {
            ktFiles.add(loadKtFile(environment.getProject(), file));
        }
        return ktFiles;
    }

    @NotNull
    public static ModuleDescriptorImpl createEmptyModule() {
        return createEmptyModule("<empty-for-test>");
    }

    @NotNull
    public static ModuleDescriptorImpl createEmptyModule(@NotNull String name) {
        return createEmptyModule(name, DefaultBuiltIns.getInstance());
    }

    @NotNull
    public static ModuleDescriptorImpl createEmptyModule(@NotNull String name, @NotNull KotlinBuiltIns builtIns) {
        return new ModuleDescriptorImpl(Name.special(name), LockBasedStorageManager.NO_LOCKS, builtIns);
    }

    @NotNull
    public static File replaceExtension(@NotNull File file, @Nullable String newExtension) {
        return new File(file.getParentFile(), FileUtil.getNameWithoutExtension(file) + (newExtension == null ? "" : "." + newExtension));
    }

    public static boolean isAllFilesPresentTest(String testName) {
        //noinspection SpellCheckingInspection
        return testName.toLowerCase().startsWith("allfilespresentin");
    }

    public static boolean isMultiExtensionName(@NotNull String name) {
        int firstDotIndex = name.indexOf('.');
        if (firstDotIndex == -1) {
            return false;
        }
        // Several extension if name contains another dot
        return name.indexOf('.', firstDotIndex + 1) != -1;
    }
}
