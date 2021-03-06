package io.github.lizhangqu.fastmultidex

import com.android.build.gradle.internal.transforms.JarMerger
import com.android.build.gradle.internal.variant.ApplicationVariantData
import com.android.builder.core.AndroidBuilder
import com.android.builder.core.DexByteCodeConverter
import com.android.builder.core.DexOptions
import com.android.builder.core.ErrorReporter
import com.android.ide.common.process.JavaProcessExecutor
import com.android.ide.common.process.ProcessException
import com.android.ide.common.process.ProcessExecutor
import com.android.ide.common.process.ProcessOutputHandler
import com.android.utils.ILogger
import com.android.xml.AndroidXPathFactory
import javassist.ClassPool
import javassist.CtClass
import javassist.NotFoundException
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.util.GFileUtils
import org.xml.sax.InputSource

import javax.xml.xpath.XPath
import javax.xml.xpath.XPathExpressionException
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry;

class FastMultidexAndroidBuilder extends AndroidBuilder {
    private Project project
    private ApplicationVariantData applicationVariantData
    private AndroidBuilder androidBuilder

    private JavaProcessExecutor javaProcessExecutor
    /**
     * Creates an AndroidBuilder.
     * <p>
     * <var>verboseExec</var> is needed on top of the ILogger due to remote exec tools not being
     * able to output info and verbose messages separately.
     *
     * @param projectId @param createdBy the createdBy String for the apk manifest.
     * @param processExecutor @param javaProcessExecutor @param errorReporter @param logger the Logger
     * @param verboseExec whether external tools are launched in verbose mode
     */
    FastMultidexAndroidBuilder(Project project, ApplicationVariantData applicationVariantData, AndroidBuilder androidBuilder, String projectId, String createdBy, ProcessExecutor processExecutor, JavaProcessExecutor javaProcessExecutor, ErrorReporter errorReporter, ILogger logger, boolean verboseExec) {
        super(projectId, createdBy, processExecutor, javaProcessExecutor, errorReporter, logger, verboseExec)
        this.project = project
        this.applicationVariantData = applicationVariantData
        this.androidBuilder = androidBuilder
        this.javaProcessExecutor = javaProcessExecutor
    }

    @Override
    void convertByteCode(Collection<File> inputs, File outDexFolder, boolean multidex, File mainDexList, DexOptions dexOptions, ProcessOutputHandler processOutputHandler) throws IOException, InterruptedException, ProcessException {
        project.logger.error("=======convertByteCode start======");
        inputs.each {
            project.logger.error("inputs ${it}")
        }
        project.logger.error("outDexFolder ${outDexFolder}")
        project.logger.error("multidex ${multidex}")
        project.logger.error("mainDexList ${mainDexList}")
        project.logger.error("dexOptions ${dexOptions}")
        project.logger.error("processOutputHandler ${processOutputHandler}")
        project.logger.error("=======convertByteCode end======");
//        super.convertByteCode(inputs, outDexFolder, multidex, mainDexList, dexOptions, processOutputHandler)
        Profiler.start()

        Profiler.enter("repackage")
        Collection<File> repackageInputs = repackage(inputs)
        Profiler.release()
    }

    Collection<File> repackage(Collection<File> inputs) throws IOException {
        if (inputs == null || inputs.size() == 0) {
            return null
        }
        File intermediatesDir = this.applicationVariantData.getScope().getGlobalScope().getIntermediatesDir()
        File repackageDir = new File(intermediatesDir, "fastmultidex/${this.applicationVariantData.getVariantConfiguration().getDirName()}")
        GFileUtils.deleteDirectory(repackageDir)
        GFileUtils.mkdirs(repackageDir)

        Collection<String> mainDexList = getMainDexList(inputs)
        List<File> jars = new ArrayList<>()
        List<File> folders = new ArrayList<>()
        inputs.each {
            if (it.isDirectory()) {
                folders.add(it)
            } else {
                jars.add(it)
            }
        }

        if (!folders.isEmpty()) {
            File mergedJar = new File(repackageDir, "jarmerge/combined.jar")
            GFileUtils.deleteQuietly(mergedJar)
            GFileUtils.touch(mergedJar)
            JarMerger jarMerger = new JarMerger(mergedJar)
            folders.each {
                jarMerger.addFolder(it)
            }
            jarMerger.close()
            if (mergedJar.length() > 0) {
                jars.add(mergedJar)
            }
        }

        List<File> result = new ArrayList<>()
        File mainDexJar = new File(repackageDir, "repackage/mainDex.jar")
        GFileUtils.deleteQuietly(mainDexJar)
        GFileUtils.touch(mainDexJar)
        JarOutputStream mainDexJarOutputStream = new JarOutputStream(
                new BufferedOutputStream(new FileOutputStream(mainDexJar)));

        jars.each { File jarFile ->
            //calculate dest file
            MessageDigest md5 = MessageDigest.getInstance("MD5")
            md5.update(jarFile.getAbsolutePath().getBytes("UTF-8"))
            BigInteger bi = new BigInteger(1, md5.digest())
            String md5Value = String.format("%032x", bi).toLowerCase()
            File destFile = new File(repackageDir, "repackage/${jarFile.getName()}_${md5Value}.jar")

            //add
            result.add(destFile)

            JarOutputStream destJarOutputStream = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(destFile)))
            JarFile sourceJarFile = new JarFile(jarFile)

            //copy to main dex jar
            List<String> addedMainDexList = new ArrayList<>();
            sourceJarFile.entries().each { JarEntry jarEntry ->
                String pathName = jarEntry.getName()
                if (mainDexList.contains(pathName)) {
                    copyStream(sourceJarFile.getInputStream(jarEntry), mainDexJarOutputStream, jarEntry, pathName)
                    addedMainDexList.add(pathName)
                }
            }

            //copy to dest jar
            if (!addedMainDexList.isEmpty()) {
                sourceJarFile.entries().each { JarEntry jarEntry ->
                    String pathName = jarEntry.getName()
                    if (!addedMainDexList.contains(pathName)) {
                        copyStream(sourceJarFile.getInputStream(jarEntry), destJarOutputStream, jarEntry, pathName);
                    }
                }
            }

            //close
            try {
                sourceJarFile.close()
            } catch (Exception e) {
            }
            try {
                destJarOutputStream.close()
            } catch (Exception e) {
            }

            //if not copy to main dex jar, then copy single file to dest
            if (addedMainDexList.isEmpty()) {
                GFileUtils.copyFile(jarFile, destFile)
            }
        }

        //close
        try {
            mainDexJarOutputStream.close()
        } catch (Exception e) {
        }

        //add
        result.add(0, mainDexJar);
        return result
    }

    @SuppressWarnings("GrMethodMayBeStatic")
    void copyStream(InputStream inputStream, JarOutputStream jos, JarEntry ze, String pathName) {
        try {
            ZipEntry newEntry = new ZipEntry(pathName)
            if (ze.getTime() != -1) {
                newEntry.setTime(ze.getTime())
                newEntry.setCrc(ze.getCrc())
            }
            jos.putNextEntry(newEntry);
            IOUtils.copy(inputStream, jos)
        } catch (Exception e) {
            e.printStackTrace()
        } finally {
            try {
                inputStream.close()
            } catch (Exception e) {

            }
        }
    }

    @SuppressWarnings("GrMethodMayBeStatic")
    String getApplicationName(File manifestFile) {
        XPath xpath = AndroidXPathFactory.newXPath()
        try {
            return xpath.evaluate("/manifest/application/@android:name",
                    new InputSource(new FileInputStream(manifestFile)))
        } catch (XPathExpressionException e) {
            // won't happen.
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e)
        }
        return null
    }

    Collection<String> getMainDexList(Collection<File> inputs) {
        Set<String> mainDexList = new HashSet<String>()
        if (inputs == null || inputs.size() == 0) {
            return mainDexList
        }
        File manifestFile = applicationVariantData.getMainOutput().processResourcesTask.getManifestFile()
        String applicationName = getApplicationName(manifestFile)

        ClassPool classPool = new ClassPool()
        inputs.each {
            if (it.isFile()) {
                classPool.insertClassPath(it.getAbsolutePath())
            } else {
                classPool.appendClassPath(it.getAbsolutePath())
            }
        }

        Set<String> rootClasses = new LinkedHashSet<>()
        rootClasses.add(applicationName)

        rootClasses.each {
            addRefClazz(classPool, it, mainDexList, "");
        }

        List<String> mainDexListClass = new ArrayList<String>()
        mainDexList.each {
            mainDexListClass.add(it.replaceAll("\\.", "/") + ".class")
        }

        File intermediatesDir = this.applicationVariantData.getScope().getGlobalScope().getIntermediatesDir()
        File mainDexListFile = new File(intermediatesDir, "fastmultidex/${this.applicationVariantData.getVariantConfiguration().getDirName()}/mainDexList.txt")
        GFileUtils.deleteQuietly(mainDexListFile)
        GFileUtils.touch(mainDexListFile)
        FileUtils.writeLines(mainDexListFile, mainDexList)
        return mainDexListClass
    }

    void addRefClazz(ClassPool classPool, String clazz, Set<String> classList, String root) {
        if (classList.contains(clazz)) {
            return
        }
        try {
            CtClass ctClass = classPool.get(clazz)
            if (null != ctClass) {
                project.logger.info("[MainDex] add " + clazz + " to main dex list referenced by " + root)
                classList.add(clazz)
                if (classList.size() > 3000) {
                    return
                }
                Collection<String> references = ctClass.getRefClasses()

                if (null == references) {
                    return
                }

                references.each {
                    addRefClazz(classPool, it, classList, root + "->" + clazz);
                }
            }
        } catch (Throwable e) {
        }
    }


    @Override
    DexByteCodeConverter getDexByteCodeConverter() {
        project.logger.error("=======getDexByteCodeConverter start======");
        def converter = super.getDexByteCodeConverter();
        project.logger.error("converter ${converter}")
        project.logger.error("=======getDexByteCodeConverter end======");
        return converter
    }
}
