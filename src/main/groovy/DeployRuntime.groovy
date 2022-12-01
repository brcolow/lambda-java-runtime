import java.io.File
import java.nio.file.Files
import java.io.FileOutputStream;
import java.net.URL
import java.io.BufferedInputStream

import groovy.json.JsonSlurper
import groovy.transform.ToString

@Grab(group='commons-io', module='commons-io', version='2.11.0')
import org.apache.commons.io.FileUtils

@Grab(group='org.apache.commons', module='commons-compress', version='1.22')
import org.apache.commons.compress.archivers.ArchiveOutputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.utils.IOUtils

@Grab(group='software.amazon.awssdk', module='lambda', version='2.18.28')
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.model.PublishLayerVersionRequest
import software.amazon.awssdk.services.lambda.model.LayerVersionContentInput
import software.amazon.awssdk.core.SdkBytes

import java.nio.file.Path
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Collectors

@ToString
class Release {
    String type
    String arch
    String os
    String impl
    String releaseDateStr
    OffsetDateTime releaseDate
    String fileName
    String link
    String mime
    String extension
    long size

    Release(Map releaseMap, Pattern regexPattern) {
        fileName = releaseMap.name
        mime = releaseMap.content_type
        size = releaseMap.size as long
        if (fileName.endsWith(".zip") || fileName.endsWith(".tar.gz")) {
            link = releaseMap.browser_download_url
        }
        final Matcher matcher = regexPattern.matcher(fileName)
        if (matcher.find()) {
            type = matcher.group(1)
            arch = matcher.group(2)
            os = matcher.group(3)
            impl = matcher.group(4)
            releaseDateStr = matcher.group(5)
            extension = matcher.group(6)
        }

        if (type == "jdk") {
            try {
                // Example release date format: 2022-09-15-04-49
                releaseDate = OffsetDateTime.of(LocalDateTime.parse(releaseDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm")), ZoneOffset.UTC);
            } catch (DateTimeParseException ex) {
                // TODO: This is a named version release like "18.0.2.1_1".
            }
        }
    }

    Release(type, arch, os, impl, releaseDateStr) {
        this.type = type
        this.arch = arch
        this.os = os
        this.impl = impl
        this.releaseDateStr = releaseDateStr
    }

    String toSafeDirectoryName() {
        return String.format(
                "ADOPTGIT_%s_%s_%s_%s_%s",
                type.toLowerCase().trim(),
                arch.toLowerCase().trim(),
                os.toLowerCase().trim(),
                impl.toLowerCase().trim(),
                releaseDateStr.toLowerCase().trim()).md5()
    }

    @Override
    boolean equals(o) {
        if (this.is(o)) return true
        if (getClass() != o.class) return false

        Release release = (Release) o

        if (type != release.type) return false
        if (arch != release.arch) return false
        if (os != release.os) return false
        if (impl != release.impl) return false
        if (releaseDateStr != release.releaseDateStr) return false

        return true
    }
}

/**
 * Fetches the last 100 Adoptium releases (pagination limit) for the given version and returns
 * them as a List of Release objects.
 *
 * @param version the version to get (pre)-releases for
 * @return the list of Release objects
 */
def getAdoptJdkReleases(version, regexPattern) {
    def releasesJson = new URL("https://api.github.com/repos/adoptium/temurin" + version + "-binaries/releases").text
    def releasesMap = new JsonSlurper().parseText(releasesJson)
    def releases = []
    releasesMap.each { releaseJson ->
        releaseJson.assets.each { releaseAssetJson ->
            Release release = new Release(releaseAssetJson, regexPattern)
            if (release.type == "jdk") {
                if (release.arch != null && release.os != null && release.impl != null && release.releaseDateStr != null) {
                    releases.add(release)
                }
            }
        }
    }
    return releases
}

/**
 * Gets the root directory of the JDK corresponding to the given Release object. If this release has not been cached
 * then it is first downloaded and extracted.
 *
 * @param release the release to get the root JDK directory for
 * @return the File object of the root JDK directory
 */
def getJdkRootDir(release) {
    def jdkRootDir = null
    // See if the requested release has already been downloaded and cached.
    def jdkCacheDir = new File(project.build.directory + "/jdkCache/")
    if (!jdkCacheDir.exists()) {
        System.out.println("First time building or JDK cache (" + jdkCacheDir.getCanonicalPath() + ") was cleaned.")
        jdkCacheDir.mkdir()
    }

    def jdkHashDir = jdkCacheDir.toPath().resolve(release.toSafeDirectoryName()).toFile()
    if (!jdkHashDir.exists()) {
        // The requested JDK has not been downloaded before (or was deleted), so download it now.
        jdkHashDir.mkdir()
        def jdkArchive = new File(jdkHashDir.getCanonicalPath() + "/" + release.fileName)
        System.out.println("Downloading JDK: " + release.fileName)
        jdkArchive.withOutputStream { out ->
            new URL(release.link).eachByte { b ->
                out.write(b)
            }
        }

        if (release.extension.equalsIgnoreCase("tar.gz")) {
            jdkRootDir = extractTarGz(jdkHashDir, jdkArchive)
        } else if (release.extension.equalsIgnoreCase("zip")) {
            jdkRootDir = extractZip(jdkHashDir, jdkArchive)
        }
    } else {
        jdkRootDir = Files.newDirectoryStream(jdkHashDir.toPath(), p -> Files.isDirectory(p)).iterator().next().toFile()
    }
    return jdkRootDir
}

/**
 * Extracts the given jdkArchive file (which must be a "tar.gz" file) to the given jdkHashDir directory and returns
 * the root directory of the extracted archive.
 *
 * @param jdkHashDir the directory inside of ./target/jdkCache that is a md5 hash of the current release
 * @param jdkArchive the tar.gz file to extract
 * @return the root directory of the extracted archive, e.g. "jdkCache/{md5_hash}/jdk-13+33"
 */
def extractTarGz(jdkHashDir, jdkArchive) {
    Path rootDir = null
    try (TarArchiveInputStream tarIn = new TarArchiveInputStream(new GzipCompressorInputStream(
            new BufferedInputStream(new FileInputStream(jdkArchive))))) {
        TarArchiveEntry entry

        while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                if (rootDir == null) {
                    rootDir = jdkHashDir.toPath().resolve(entry.getName())
                }
                Files.createDirectory(jdkHashDir.toPath().resolve(entry.getName()));
            } else {
                FileOutputStream fos = new FileOutputStream(jdkHashDir.toPath().resolve(entry.getName()).toFile())
                try (BufferedOutputStream dest = new BufferedOutputStream(fos, 8192)) {
                    IOUtils.copy(tarIn, dest)
                }
            }
        }
        tarIn.close()
    }
    return rootDir.toFile()
}

/**
 * Extracts the given jdkArchive file (which must be a "zip" file) to the given jdkHashDir directory and returns
 * the root directory of the extracted archive.
 *
 * @param jdkHashDir the directory inside of ./target/jdkCache that is a md5 hash of the current release
 * @param jdkArchive the zip file to extract
 * @return the root directory of the extracted archive, e.g. "jdkCache/{md5_hash}/jdk-13+33
 */
def extractZip(jdkHashDir, jdkArchive) {
    Path rootDir = null
    try (ZipArchiveInputStream zipIn = new ZipArchiveInputStream(new BufferedInputStream(
            new FileInputStream(jdkArchive)), null, true)) {
        ArchiveEntry entry
        while ((entry = zipIn.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                if (rootDir == null) {
                    rootDir = jdkHashDir.toPath().resolve(entry.getName())
                }
                Files.createDirectory(jdkHashDir.toPath().resolve(entry.getName()))
            } else {
                FileOutputStream fos = new FileOutputStream(jdkHashDir.toPath().resolve(entry.getName()).toFile())
                try (BufferedOutputStream dest = new BufferedOutputStream(fos, 8192)) {
                    IOUtils.copy(zipIn, dest)
                }
            }
        }
        zipIn.close()
    }
    return rootDir.toFile()
}

// Example filename for a JDK: OpenJDK18U-jdk_x64_alpine-linux_hotspot_18_36.tar.gz
def releases = getAdoptJdkReleases(repoVersion, Pattern.compile("OpenJDK${repoVersion}U-([a-z]+)_([0-9a-z\\-]+)_([0-9a-z\\-]+)_([0-9a-z]+)[_|\\-]([0-9\\-_]+)\\.(.+)"))
Release release
if (releaseDate == '$latest') {
    def latestLinuxReleaseWithCorrespondingWindowsRelease = releases.stream()
            .filter(r -> r.releaseDateStr != null)
            .filter(r -> r.type == type && r.arch == arch && r.os == os && r.impl == impl)
            .sorted(Comparator.comparing(r -> r.releaseDateStr))
            .filter(r -> releases.contains(new Release(r.type, r.arch, "windows", r.impl, r.releaseDateStr)))
            .findFirst()
    if (latestLinuxReleaseWithCorrespondingWindowsRelease.isPresent()) {
        System.out.println("Latest JDK release with ${arch} and windows artifacts: ${latestLinuxReleaseWithCorrespondingWindowsRelease.get().fileName}")
    } else {
        throw new RuntimeException("There is no release for JDK ${repoVersion} that has linux and windows artifacts.")
    }
    release = latestLinuxReleaseWithCorrespondingWindowsRelease.get()
} else {
// Get the release corresponding to the properties configured in gmaven-plus plugin config (in pom.xml).
    def releaseOpt = releases.stream().filter { r -> r.equals(new Release(type, arch, os, impl, releaseDate)) }.findFirst()
    if (!releaseOpt.isPresent()) {
        throw new IllegalArgumentException("Could not find JDK release for version = " + repoVersion + ", type = " +
                type + ", arch = " + arch + ", os = " + os + ", impl = " + impl + ", releaseDate = " + releaseDate)
    }
    release = releaseOpt.get()
}
System.out.println("Using release: \"" + release.fileName + "\".")

def jdkRootDir = getJdkRootDir(release)
System.out.println("JDK root directory: \"" + jdkRootDir + "\".")

// If we are on Windows then we need to download the JDK that corresponds exactly to the release JDK except
// instead of for linux, for windows, *and then* use the jlink binary contained in that - not the one in the linux JDK.
def jdkWinRootDir = null
if (System.properties['os.name'].toLowerCase().contains('windows')) {
    def winReleases = releases.stream().filter { r -> r.equals(new Release(type, arch,
            "windows", impl, releaseDate))}.collect(Collectors.toList())

    def winZipRelease = null
    winReleases.each { winRelease ->
        if (winRelease.extension.equalsIgnoreCase("zip")) {
            System.out.println("Found corresponding Windows (ZIP) release: " + winRelease)
            winZipRelease = winRelease
        }
    }

    if (winZipRelease == null) {
        throw new IllegalArgumentException("Could not find corresponding Windows JDK release for version = " + repoVersion + ", type = " +
                type + ", arch = " + arch + ", os = windows" + ", impl = " + impl, ", extension = zip")
    }
    jdkWinRootDir = getJdkRootDir(winZipRelease)
}

// Delete target/dist
def distributionDir =  new File(project.build.directory + "/dist")
if (distributionDir.exists()) {
    System.out.println("Deleting existing output folder: \"" + distributionDir.getCanonicalPath() + "\".")
    FileUtils.forceDelete(distributionDir)
}

def modulePathSeparator = System.properties['os.name'].toLowerCase().contains('windows') ? ';' : ':'
Process process = new ProcessBuilder(
        (jdkWinRootDir != null ? jdkWinRootDir.toString() : jdkRootDir.toString()) + "/bin/jlink",
        "--output", distributionDir.getCanonicalPath(),
        "--launcher", "bootstrap=com.dow.aws.lambda/com.dow.aws.lambda.Bootstrap",
        "--compress=2", "--no-header-files", "--no-man-pages",
        // "--strip-debug",
        // Consider using --strip-native-debug-symbols instead (only supported on Linux).
        // --vm server ?
        "--module-path", jdkRootDir.toString() + "/jmods" + modulePathSeparator +
        project.build.directory + "/lib" + modulePathSeparator +
        project.build.directory + "/classes",
        "--add-modules", "com.dow.aws.lambda," + jlinkModules)
        .inheritIO()
        .start()

def exitCode = process.waitFor()
if (exitCode != 0) {
    System.out.println("Non-zero exit code from jlink: " + exitCode)
    System.exit(-1)
}

// rm -rf ./layer && mkdir ./layer
def layerDir = new File(project.build.directory + "/layer")
layerDir.deleteDir()
layerDir.mkdirs()

// touch ./layer/bootstrap && echo "$BOOTSTRAP_SCRIPT" > ./layer/bootstrap
def bootstrapScript = new File(project.build.directory + "/layer/bootstrap")
bootstrapScript.write('''
#!/bin/sh
/opt/dist/bin/bootstrap
'''.stripIndent().trim())
bootstrapScript.setExecutable(true, false)

def generatedLauncherScript = new File(project.build.directory + "/dist/bin/bootstrap")
def newText = generatedLauncherScript.text
// It seems that making a JFR recording is not possible when CDS is enabled - even though the error message makes it
// seem as though the recording won't be active during CDS dumping, the JFR file never gets created:
//  -XX:StartFlightRecording=delay=2s,dumponexit=true

//  #-Xlog:class+load=info -Xlog:cds -Xlog:cds+dynamic=debug
// It seems that Java 14 added an unstable plugin to jlink called "--add-options" which we could use when invoking jlink
// so we don't have to do this replace business:
//  --add-options <options>   Prepend the specified <options> string, which may
//                            include whitespace, before any other options when
//                            invoking the virtual machine in the resulting image.
newText = newText.replace('JLINK_VM_OPTIONS=',
'''
dynamicArchive="/tmp/archive.jsa"
if [ ! -f "$archiveFile" ]; then
  archiveArg="-XX:ArchiveClassesAtExit"
else
  archiveArg="-XX:SharedArchiveFile"
fi
export AWS_EXECUTION_ENV=AWS_Lambda_java11
JLINK_VM_OPTIONS="$archiveArg=$dynamicArchive -XX:+UseCompressedOops -XX:+UseG1GC -XX:+UseCompressedClassPointers -Xshare:on -Xlog:cds=warning"
''' + project.properties['aws.lambda.test'] == "true" ?
        "export AWS_LAMBDA_TEST=true\n" : "")

generatedLauncherScript.newWriter().withWriter {w -> w << newText}

// cp -r ./dist ./layer/dist
new groovy.ant.AntBuilder().copy(todir: project.build.directory + "/layer/dist") {
    fileset( dir: project.build.directory + "/dist" )
}

// In case ./layer/dist/lib/server/classes.jsa doesn't exist we need to generate the base CDS archives.
// This needs to be done in a Linux environment as we need to run "java -Xshare:dump" on the Linux JVM we
// are bundling with. TODO: Check if classes.jsa exists already and if so don't run this snippet.
System.out.println("Running java -Xshare:dump on Linux JVM to generate base classes.jsa archive (requires Windows Subsystem for Linux)...")
ProcessBuilder dumpProcessBuilder = new ProcessBuilder("bash.exe", "-c", "\"" + '\"$(wslpath -a \'' + "$project.build.directory" + "')\"/layer/dist/bin/java -Xmx248M -Xshare:dump\"")
        .inheritIO();
Process dumpProcess = dumpProcessBuilder.start()
exitCode = dumpProcess.waitFor()
if (exitCode != 0) {
    System.out.println("Non-zero exit code from java -Xshare:dump: " + exitCode)
    System.exit(-1)
}
dumpProcess.destroy()

// zip -r layer.zip ./layer/*
def zipFile = new File(project.build.directory + "/layer.zip")
zipFile.delete()

// The reason this is so convoluted and complex (instead of simply using AntBuilder().zip is because we need
// the executable permissions of the bootstrap script to be kept during zipping. This method works regardless
// of the OS this build is running on. Without these permissions AWS refuses to run the bootstrap script:
// Error: Runtime failed to start: fork/exec /opt/bootstrap: permission denied
def archiveStream = new FileOutputStream(zipFile);
ArchiveOutputStream archive = new ArchiveStreamFactory().createArchiveOutputStream(
        ArchiveStreamFactory.ZIP, archiveStream)
Collection<File> fileList = FileUtils.listFiles(layerDir, null, true)
for (File file : fileList) {
    int index = layerDir.getAbsolutePath().length() + 1
    String path = file.getCanonicalPath()
    ZipArchiveEntry entry = new ZipArchiveEntry(path.substring(index))
    entry.setUnixMode(040777)
    archive.putArchiveEntry(entry)
    BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))
    IOUtils.copy(input, archive)
    input.close()
    archive.closeArchiveEntry()
}
archive.finish()
archiveStream.close()

LambdaClient lambdaClient = LambdaClient.builder()
        .region(Region.of(awsRegion)).build()

// aws lambda publish-layer-version --layer-name DOW_Lambda_Java_Runtime --zip-file fileb://layer.zip
String layerName
if (project.properties['aws.lambda.test'] == "true") {
    layerName = "Custom_Java_Test_Runtime"
} else {
    layerName = "Custom_Java_Runtime"
}

if (project.properties['aws.lambda.publish'] == "true") {
    def publishLayerRequest = PublishLayerVersionRequest.builder()
            .layerName(layerName)
            .description(String.format("%s runtime.", jdkRootDir.getName()))
            .content(LayerVersionContentInput.builder()
                    .zipFile(SdkBytes.fromByteArray(Files.readAllBytes(zipFile.toPath()))).build()).build()

    def publishLayerResult = lambdaClient.publishLayerVersion(publishLayerRequest)

    System.out.println("Layer Version ARN: " + publishLayerResult.layerVersionArn())
} else {
    System.out.println("Skipping publishing as \"aws.lambda.publish\" Maven property is not \"true\".")
}