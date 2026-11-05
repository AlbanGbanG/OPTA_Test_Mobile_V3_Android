package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Petit lanceur Gradle transparent, fourni uniquement parce que l'environnement
 * de génération ne peut pas joindre le binaire officiel gradle-wrapper.jar.
 * Il lit gradle-wrapper.properties, télécharge la distribution déclarée et la lance.
 */
public final class GradleWrapperMain {
    private GradleWrapperMain() {}

    public static void main(String[] args) throws Exception {
        File appHome = new File(System.getProperty("wrapper.appHome", System.getProperty("user.dir"))).getCanonicalFile();
        File propertiesFile = new File(appHome, "gradle/wrapper/gradle-wrapper.properties");
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(propertiesFile)) {
            properties.load(in);
        }

        String distributionUrl = properties.getProperty("distributionUrl");
        if (distributionUrl == null || distributionUrl.trim().isEmpty()) {
            throw new IllegalStateException("distributionUrl absent de " + propertiesFile);
        }

        String fileName = distributionUrl.substring(distributionUrl.lastIndexOf('/') + 1);
        String version = fileName.replace("gradle-", "").replace("-bin.zip", "").replace("-all.zip", "");
        File cache = new File(appHome, ".gradle-bootstrap");
        File distributionHome = new File(cache, "gradle-" + version);
        File zipFile = new File(cache, fileName);

        if (!new File(distributionHome, executableRelativePath()).isFile()) {
            if (!cache.isDirectory() && !cache.mkdirs()) {
                throw new IOException("Impossible de créer " + cache);
            }
            System.out.println("Téléchargement de Gradle " + version + "…");
            downloadFollowingRedirects(new URL(distributionUrl), zipFile.toPath());
            unzip(zipFile.toPath(), cache.toPath());
        }

        File executable = new File(distributionHome, executableRelativePath());
        if (!isWindows()) executable.setExecutable(true);

        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(Arrays.asList(args));

        Process process = new ProcessBuilder(command)
            .directory(appHome)
            .inheritIO()
            .start();
        System.exit(process.waitFor());
    }

    private static void downloadFollowingRedirects(URL initialUrl, Path target) throws IOException {
        URL current = initialUrl;
        for (int redirect = 0; redirect < 10; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", "OPTA-Test-Mobile-Gradle-Bootstrap");
            int code = connection.getResponseCode();

            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirection sans destination");
                current = new URL(current, location);
                continue;
            }

            if (code < 200 || code >= 300) {
                connection.disconnect();
                throw new IOException("Téléchargement Gradle refusé : HTTP " + code);
            }

            Path temporary = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        throw new IOException("Trop de redirections pendant le téléchargement Gradle");
    }

    private static void unzip(Path zip, Path destination) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip.toFile())))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination.normalize())) {
                    throw new IOException("Entrée ZIP non sûre : " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (FileOutputStream out = new FileOutputStream(output.toFile())) {
                        byte[] buffer = new byte[16_384];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static String executableRelativePath() {
        return isWindows() ? "bin/gradle.bat" : "bin/gradle";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
