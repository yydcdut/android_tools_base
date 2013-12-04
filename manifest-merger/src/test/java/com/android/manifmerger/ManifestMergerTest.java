/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.manifmerger;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.manifmerger.IMergerLog.FileAndLine;
import com.android.sdklib.mock.MockLog;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.w3c.dom.Document;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Some utilities to reduce repetitions in the {@link ManifestMergerTest}s.
 * <p/>
 * See {@link #loadTestData(String)} for an explanation of the data file format.
 */
public class ManifestMergerTest extends TestCase {

    /**
     * Delimiter that indicates the test must fail.
     * An XML output and errors are still generated and checked.
     */
    private static final String DELIM_FAILS  = "fails";
    /**
     * Delimiter that starts a library XML content.
     * The delimiter name must be in the form {@code @libSomeName} and it will be
     * used as the base for the test file name. Using separate lib names is encouraged
     * since it makes the error output easier to read.
     */
    private static final String DELIM_LIB    = "lib";
    /**
     * Delimiter that starts the main manifest XML content.
     */
    private static final String DELIM_MAIN   = "main";
    /**
     * Delimiter that starts the resulting XML content, whatever is generated by the merge.
     */
    private static final String DELIM_RESULT = "result";
    /**
     * Delimiter that starts the SdkLog output.
     * The logger prints each entry on its lines, prefixed with E for errors,
     * W for warnings and P for regular printfs.
     */
    private static final String DELIM_ERRORS = "errors";
    /**
     * Delimiter for starts a section that declares how to inject an attribute.
     * The section is composed of one or more lines with the
     * syntax: "/node/node|attr-URI attrName=attrValue".
     * This is essentially a pseudo XPath-like expression that is described in
     * {@link ManifestMerger#process(Document, File[], Map, String)}.
     */
    private static final String DELIM_INJECT_ATTR   = "inject";
    /**
     * Delimiter for a section that declares how to toggle a ManifMerger option.
     * The section is composed of one or more lines with the
     * syntax: "functionName=false|true".
     */
    private static final String DELIM_FEATURES      = "features";

    /**
     * Delimiter for a section that declares how to override the package.
     * The section is composed of one line containing the new package name.
     */
    private static final String DELIM_PACKAGE       = "package";


    /*
     * Wait, I hear you, where are the tests?
     *
     * processTestFiles() uses loadTestData(), which uses one of the data filename
     * indicated below.
     *
     * We could simplify this even further by dynamically finding the data
     * files to use; however there's some value in having tests break when out
     * of sync with the known data file set.
     */
    private static String[] sDataFiles = new String[] {
        "00_noop",
        "01_ignore_app_attr",
        "02_ignore_instrumentation",
        "03_inject_attributes",
        "04_inject_attributes",
        "05_inject_package",
        "10_activity_merge",
        "11_activity_dup",
        "12_alias_dup",
        "13_service_dup",
        "14_receiver_dup",
        "15_provider_dup",
        "16_fqcn_merge",
        "17_fqcn_conflict",
        "20_uses_lib_merge",
        "21_uses_lib_errors",
        "25_permission_merge",
        "26_permission_dup",
        "28_uses_perm_merge",
        "30_uses_sdk_ok",
        "32_uses_sdk_minsdk_ok",
        "33_uses_sdk_minsdk_conflict",
        "36_uses_sdk_targetsdk_warning",
        "40_uses_feat_merge",
        "41_uses_feat_errors",
        "45_uses_feat_gles_once",
        "47_uses_feat_gles_conflict",
        "50_uses_conf_warning",
        "52_support_screens_warning",
        "54_compat_screens_warning",
        "56_support_gltext_warning",
        "60_merge_order",
        "65_override_app",
        "66_remove_app",
        "67_override_activities",
        "68_override_uses",
        "69_remove_uses",
        "70_expand_fqcns",
        "71_extract_package_prefix",
        "75_app_metadata_merge",
        "76_app_metadata_ignore",
        "77_app_metadata_conflict",
    };

    /**
     * This overrides the default test suite created by junit.
     * The test suite is a bland TestSuite with a dedicated name.
     * We inject as many instances of {@link ManifestMergerTest} in the suite
     * as we have declared data files above.
     *
     * @return A new {@link TestSuite}.
     */
    public static Test suite() {
        TestSuite suite = new TestSuite();
        // Give a non-generic name to our test suite, for better unit reports.
        suite.setName("ManifestMergerTestSuite");

        for (String fileName : sDataFiles) {
            suite.addTest(TestSuite.createTest(ManifestMergerTest.class, fileName));
        }

        return suite;
    }


    /**
     * Default constructor invoked by {@link TestSuite#createTest(Class, String)}.
     *
     * @param testName The test name provided to {@code TestSuite.createTest()}.
     *                 This is later accessible via {@link #getName()}.
     */
    public ManifestMergerTest(String testName) {
        super(testName);
    }

    /**
     * Invoked by the test framework to run the specific test which name
     * has been passed to the constructor.
     * Note that we create one instance of this class per test to run in
     * the associated {@link TestSuite}.
     */
    @Override
    protected void runTest() throws Throwable {
        String testName = getName();
        assertNotNull(testName);
        processTestFiles(loadTestData(testName));
    }


    static class TestFiles {
        private final File mMain;
        private final File[] mLibs;
        private final Map<String, String> mInjectAttributes;
        private final String mPackageOverride;
        private final File mActualResult;
        private final String mExpectedResult;
        private final String mExpectedErrors;
        private final boolean mShouldFail;
        private final Map<String, Boolean> mFeatures;

        /** Files used by a given test case. */
        public TestFiles(
                boolean shouldFail,
                @NonNull File main,
                @NonNull File[] libs,
                @NonNull Map<String, Boolean> features,
                @NonNull Map<String, String> injectAttributes,
                @Nullable String packageOverride,
                @NonNull File actualResult,
                @NonNull String expectedResult,
                @NonNull String expectedErrors) {
            mShouldFail = shouldFail;
            mMain = main;
            mLibs = libs;
            mFeatures = features;
            mPackageOverride = packageOverride;
            mInjectAttributes = injectAttributes;
            mActualResult = actualResult;
            mExpectedResult = expectedResult;
            mExpectedErrors = expectedErrors;
        }

        public boolean getShouldFail() {
            return mShouldFail;
        }

        @NonNull
        public File getMain() {
            return mMain;
        }

        @NonNull
        public File[] getLibs() {
            return mLibs;
        }

        @NonNull
        public Map<String, Boolean> getFeatures() {
            return mFeatures;
        }

        @NonNull
        public Map<String, String> getInjectAttributes() {
            return mInjectAttributes;
        }

        @Nullable
        public String getPackageOverride() {
            return mPackageOverride;
        }

        @NonNull
        public File getActualResult() {
            return mActualResult;
        }

        @NonNull
        public String getExpectedResult() {
            return mExpectedResult;
        }

        public String getExpectedErrors() {
            return mExpectedErrors;
        }

        // Try to delete any temp file potentially created.
        public void cleanup() {
            if (mMain != null && mMain.isFile()) {
                mMain.delete();
            }

            if (mActualResult != null && mActualResult.isFile()) {
                mActualResult.delete();
            }

            for (File f : mLibs) {
                if (f != null && f.isFile()) {
                    f.delete();
                }
            }
        }
    }

    /**
     * Calls {@link #loadTestData(String)} by
     * inferring the data filename from the caller's method name.
     * <p/>
     * The caller method name must be composed of "test" + the leaf filename.
     * Extensions ".xml" or ".txt" are implied.
     * <p/>
     * E.g. to use the data file "12_foo.xml", simply call this from a method
     * named "test12_foo".
     *
     * @return A new {@link TestFiles} instance. Never null.
     * @throws Exception when things go wrong.
     * @see #loadTestData(String)
     */
    @NonNull
    TestFiles loadTestData() throws Exception {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 0, n = stack.length; i < n; i++) {
            StackTraceElement caller = stack[i];
            String name = caller.getMethodName();
            if (name.startsWith("test")) {
                return loadTestData(name.substring(4));
            }
        }

        throw new IllegalArgumentException("No caller method found which name started with 'test'");
    }

    /**
     * Loads test data for a given test case.
     * The input (main + libs) are stored in temp files.
     * A new destination temp file is created to store the actual result output.
     * The expected result is actually kept in a string.
     * <p/>
     * Data File Syntax:
     * <ul>
     * <li> Lines starting with # are ignored (anywhere, as long as # is the first char).
     * <li> Lines before the first {@code @delimiter} are ignored.
     * <li> Empty lines just after the {@code @delimiter}
     *      and before the first &lt; XML line are ignored.
     * <li> Valid delimiters are {@code @main} for the XML of the main app manifest.
     * <li> Following delimiters are {@code @libXYZ}, read in the order of definition.
     *      The name can be anything as long as it starts with "{@code @lib}".
     * </ul>
     *
     * @param filename The test data filename. If no extension is provided, this will
     *   try with .xml or .txt. Must not be null.
     * @return A new {@link TestFiles} instance. Must not be null.
     * @throws Exception when things fail to load properly.
     */
    @NonNull
    TestFiles loadTestData(@NonNull String filename) throws Exception {

        String resName = "data" + File.separator + filename;
        InputStream is = null;
        BufferedReader reader = null;
        BufferedWriter writer = null;

        try {
            is = this.getClass().getResourceAsStream(resName);
            if (is == null && !filename.endsWith(".xml")) {
                String resName2 = resName + ".xml";
                is = this.getClass().getResourceAsStream(resName2);
                if (is != null) {
                    filename = resName2;
                }
            }
            if (is == null && !filename.endsWith(".txt")) {
                String resName3 = resName + ".txt";
                is = this.getClass().getResourceAsStream(resName3);
                if (is != null) {
                    filename = resName3;
                }
            }
            assertNotNull("Test data file not found for " + filename, is);

            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

            // Get the temporary directory to use. Just create a temp file, extracts its
            // directory and remove the file.
            File tempFile = File.createTempFile(this.getClass().getSimpleName(), ".tmp");
            File tempDir = tempFile.getParentFile();
            if (!tempFile.delete()) {
                tempFile.deleteOnExit();
            }

            String line = null;
            String delimiter = null;
            boolean skipEmpty = true;

            boolean shouldFail = false;
            Map<String, Boolean> features = new HashMap<String, Boolean>();
            String packageOverride = null;
            Map<String, String> injectAttributes = new HashMap<String, String>();
            StringBuilder expectedResult = new StringBuilder();
            StringBuilder expectedErrors = new StringBuilder();
            File mainFile = null;
            File actualResultFile = null;
            List<File> libFiles = new ArrayList<File>();
            int tempIndex = 0;

            while ((line = reader.readLine()) != null) {
                if (skipEmpty && line.trim().isEmpty()) {
                    continue;
                }
                if (!line.isEmpty() && line.charAt(0) == '#') {
                    continue;
                }
                if (!line.isEmpty() && line.charAt(0) == '@') {
                    delimiter = line.substring(1);
                    assertTrue(
                        "Unknown delimiter @" + delimiter + " in " + filename,
                        delimiter.startsWith(DELIM_LIB) ||
                        delimiter.equals(DELIM_MAIN)    ||
                        delimiter.equals(DELIM_RESULT)  ||
                        delimiter.equals(DELIM_ERRORS)  ||
                        delimiter.equals(DELIM_FAILS)   ||
                        delimiter.equals(DELIM_FEATURES) ||
                        delimiter.equals(DELIM_INJECT_ATTR) ||
                        delimiter.equals(DELIM_PACKAGE));

                    skipEmpty = true;

                    if (writer != null) {
                        try {
                            writer.close();
                        } catch (IOException ignore) {}
                        writer = null;
                    }

                    if (delimiter.equals(DELIM_FAILS)) {
                        shouldFail = true;

                    } else if (!delimiter.equals(DELIM_ERRORS) &&
                               !delimiter.equals(DELIM_FEATURES) &&
                               !delimiter.equals(DELIM_INJECT_ATTR) &&
                               !delimiter.equals(DELIM_PACKAGE)) {
                        tempFile = new File(tempDir, String.format("%1$s%2$d_%3$s.xml",
                                this.getClass().getSimpleName(),
                                tempIndex++,
                                delimiter.replaceAll("[^a-zA-Z0-9_-]", "")
                                ));
                        tempFile.deleteOnExit();

                        if (delimiter.startsWith(DELIM_LIB)) {
                            libFiles.add(tempFile);

                        } else if (delimiter.equals(DELIM_MAIN)) {
                            mainFile = tempFile;

                        } else if (delimiter.equals(DELIM_RESULT)) {
                            actualResultFile = tempFile;

                        } else {
                            fail("Unexpected data file delimiter @" + delimiter +
                                 " in " + filename);
                        }

                        if (!delimiter.equals(DELIM_RESULT)) {
                            writer = new BufferedWriter(new FileWriter(tempFile));
                        }
                    }

                    continue;
                }
                if (delimiter != null &&
                        skipEmpty &&
                        !line.isEmpty() &&
                        line.charAt(0) != '#' &&
                        line.charAt(0) != '@') {
                    skipEmpty = false;
                }
                if (writer != null) {
                    writer.write(line);
                    writer.write('\n');
                } else if (DELIM_RESULT.equals(delimiter)) {
                    expectedResult.append(line).append('\n');
                } else if (DELIM_ERRORS.equals(delimiter)) {
                    expectedErrors.append(line).append('\n');
                } else if (DELIM_INJECT_ATTR.equals(delimiter)) {
                    String[] in = line.split("=");
                    if (in != null && in.length == 2) {
                        injectAttributes.put(in[0], "null".equals(in[1]) ? null : in[1]);
                    }
                } else if (DELIM_FEATURES.equals(delimiter)) {
                    String[] in = line.split("=");
                    if (in != null && in.length == 2) {
                        features.put(in[0], Boolean.parseBoolean(in[1]));
                    }
                } else if (DELIM_PACKAGE.equals(delimiter)) {
                    if (packageOverride == null) {
                        packageOverride = line;
                    }
                }
            }

            assertNotNull("Missing @" + DELIM_MAIN + " in " + filename, mainFile);
            assertNotNull("Missing @" + DELIM_RESULT + " in " + filename, actualResultFile);

            assert mainFile != null;
            assert actualResultFile != null;

            Collections.sort(libFiles);

            return new TestFiles(
                    shouldFail,
                    mainFile,
                    libFiles.toArray(new File[libFiles.size()]),
                    features,
                    injectAttributes,
                    packageOverride,
                    actualResultFile,
                    expectedResult.toString(),
                    expectedErrors.toString());

        } catch (UnsupportedEncodingException e) {
            // BufferedReader failed to decode UTF-8, O'RLY?
            throw e;

        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignore) {}
            }
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignore) {}
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignore) {}
            }
        }
    }

//    /**
//     * Loads the data test files using {@link #loadTestData()} and then
//     * invokes {@link #processTestFiles(TestFiles)} to test them.
//     *
//     * @see #loadTestData()
//     * @see #processTestFiles(TestFiles)
//     */
//    void processTestFiles() throws Exception {
//        processTestFiles(loadTestData());
//    }

    /**
     * Processes the data from the given {@link TestFiles} by
     * invoking {@link ManifestMerger#process(File, File, File[], Map, String)}:
     * the given library files are applied consecutively to the main XML
     * document and the output is generated.
     * <p/>
     * Then the expected and actual outputs are loaded into a DOM,
     * dumped again to a String using an XML transform and compared.
     * This makes sure only the structure is checked and that any
     * formatting is ignored in the comparison.
     *
     * @param testFiles The test files to process. Must not be null.
     * @throws Exception when this go wrong.
     */
    void processTestFiles(TestFiles testFiles) throws Exception {
        MockLog log = new MockLog();
        IMergerLog mergerLog = MergerLog.wrapSdkLog(log);
        ManifestMerger merger = new ManifestMerger(mergerLog, new ICallback() {
            @Override
            public int queryCodenameApiLevel(@NonNull String codename) {
                if ("ApiCodename1".equals(codename)) {
                    return 1;
                } else if ("ApiCodename10".equals(codename)) {
                    return 10;
                }
                return ICallback.UNKNOWN_CODENAME;
            }
        });

        for (Entry<String, Boolean> feature : testFiles.getFeatures().entrySet()) {
            Method m = merger.getClass().getMethod(
                        feature.getKey(),
                        new Class<?>[] { boolean.class } );
            m.invoke(merger, new Object[] { feature.getValue() } );
        }

        boolean processOK = merger.process(testFiles.getActualResult(),
                                  testFiles.getMain(),
                                  testFiles.getLibs(),
                                  testFiles.getInjectAttributes(),
                                  testFiles.getPackageOverride());

        // Convert relative pathnames to absolute.
        String expectedErrors = testFiles.getExpectedErrors().trim();
        expectedErrors = expectedErrors.replaceAll(testFiles.getMain().getName(),
                testFiles.getMain().getAbsolutePath());
        for (File file : testFiles.getLibs()) {
            expectedErrors = expectedErrors.replaceAll(file.getName(), file.getAbsolutePath());
        }

        StringBuilder actualErrors = new StringBuilder();
        for (String s : log.getMessages()) {
            actualErrors.append(s);
            if (!s.endsWith("\n")) {
                actualErrors.append('\n');
            }
        }
        assertEquals("Error generated during merging",
                expectedErrors, actualErrors.toString().trim());

        if (testFiles.getShouldFail()) {
            assertFalse("Merge process() returned true, expected false", processOK);
        } else {
            assertTrue("Merge process() returned false, expected true", processOK);
        }

        // Test result XML. There should always be one created
        // since the process action does not stop on errors.
        log.clear();
        Document document = MergerXmlUtils.parseDocument(testFiles.getActualResult(), mergerLog,
                merger);
        assertNotNull(document);
        assert document != null; // for Eclipse null analysis
        String actual = MergerXmlUtils.printXmlString(document, mergerLog);
        assertEquals("Error parsing actual result XML", "[]", log.toString());
        log.clear();
        document = MergerXmlUtils.parseDocument(
                testFiles.getExpectedResult(),
                mergerLog,
                new FileAndLine("<expected-result>", 0));
        assertNotNull("Failed to parse result document: " + testFiles.getExpectedResult(),document);
        assert document != null;
        String expected = MergerXmlUtils.printXmlString(document, mergerLog);
        assertEquals("Error parsing expected result XML", "[]", log.toString());
        assertEquals("Error comparing expected to actual result", expected, actual);

        testFiles.cleanup();
    }

}
