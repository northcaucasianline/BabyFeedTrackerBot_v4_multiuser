package org.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BackupService {
    private static final String ARCHIVE_DIR = "archive";
    private static final String USER_FILE_PATTERN = "babyfeedbot_";
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    public static void createArchiveDir() throws IOException {
        Path archivePath = Paths.get(ARCHIVE_DIR);
        if (!Files.exists(archivePath)) {
            Files.createDirectory(archivePath);
        }
    }

    public static void archiveNow() throws IOException {
        String dateStr = LocalDate.now(MOSCOW_ZONE).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        Path currentDir = Paths.get(".");
        Files.list(currentDir)
                .filter(path -> path.getFileName().toString().startsWith(USER_FILE_PATTERN) && path.getFileName().toString().endsWith(".csv"))
                .forEach(source -> {
                    try {
                        String fileName = source.getFileName().toString();
                        Path target = Paths.get(ARCHIVE_DIR, fileName.replace(".csv", "_" + dateStr + ".csv"));
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        System.out.println("Архивировано " + fileName + " в " + target);
                    } catch (IOException e) {
                        System.err.println("Ошибка архивирования: " + e.getMessage());
                    }
                });
    }

    public static void scheduleDailyArchive() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        LocalDateTime now = LocalDateTime.now(MOSCOW_ZONE);
        LocalDateTime nextMidnight = now.truncatedTo(ChronoUnit.DAYS).plusDays(1);
        long initialDelay = ChronoUnit.SECONDS.between(now, nextMidnight);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                archiveNow();
            } catch (IOException e) {
                System.err.println("Ошибка архивирования: " + e.getMessage());
            }
        }, initialDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }
}
