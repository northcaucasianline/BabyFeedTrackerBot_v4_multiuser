package org.example;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class Storage {
    private static final String DELIMITER = ";";
    private static final String PREF_FILE = "preferences.dat";
    private static final String TZ_FILE = "timezones.dat";
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, List<Record>> cache = new ConcurrentHashMap<>();

    private String getUserFile(long userId) {
        return "babyfeedbot_" + userId + ".csv";
    }

    public void loadCacheIfNeeded() throws IOException {
        // Lazy loading per user, so no global load needed
    }

    private void ensureUserLoaded(long userId) throws IOException {
        if (!cache.containsKey(userId)) {
            lock.lock();
            try {
                if (!cache.containsKey(userId)) {
                    loadUserRecords(userId);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void loadUserRecords(long userId) throws IOException {
        Path path = Paths.get(getUserFile(userId));
        List<Record> records = new ArrayList<>();
        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(DELIMITER, -1);
                    if (parts.length == 7) {
                        try {
                            int id = Integer.parseInt(parts[0]);
                            long uId = Long.parseLong(parts[1]);
                            String date = parts[2];
                            String time = parts[3];
                            int amountMl = Integer.parseInt(parts[4]);
                            String regurg = parts[5];
                            String createdAt = parts[6];
                            if (uId == userId) {
                                records.add(new Record(id, uId, date, time, amountMl, regurg, createdAt));
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid line: " + line);
                        }
                    }
                }
            }
        }
        cache.put(userId, records);
    }

    private void writeUserRecords(long userId) throws IOException {
        List<Record> records = cache.get(userId);
        if (records == null) return;
        String userFile = getUserFile(userId);
        Path tmpPath = Paths.get(userFile + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
            for (Record r : records) {
                writer.write(String.join(DELIMITER,
                        String.valueOf(r.getId()),
                        String.valueOf(r.getUserId()),
                        r.getDate(),
                        r.getTime(),
                        String.valueOf(r.getAmountMl()),
                        r.getRegurg(),
                        r.getCreatedAt()));
                writer.newLine();
            }
        }
        Files.move(tmpPath, Paths.get(userFile), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public int addRecord(long userId, String date, String time, int amountMl, String regurg) throws IOException {
        ensureUserLoaded(userId);
        lock.lock();
        try {
            List<Record> userRecords = cache.get(userId);
            int nextId = userRecords.stream().mapToInt(Record::getId).max().orElse(0) + 1;
            String createdAt = Utils.getCurrentCreatedAt();
            Record newRecord = new Record(nextId, userId, date, time, amountMl, regurg, createdAt);
            userRecords.add(newRecord);
            writeUserRecords(userId);
            return nextId;
        } finally {
            lock.unlock();
        }
    }

    public List<Record> listRecords(long userId) throws IOException {
        ensureUserLoaded(userId);
        return cache.get(userId).stream()
                .sorted(Comparator.comparing(r -> Utils.parseToLocalDateTime(r.getDate(), r.getTime())))
                .collect(Collectors.toList());
    }

    public List<Record> listRecordsByDate(long userId, String date) throws IOException {
        ensureUserLoaded(userId);
        return cache.get(userId).stream()
                .filter(r -> r.getDate().equals(date))
                .sorted(Comparator.comparing(r -> Utils.parseToLocalDateTime(r.getDate(), r.getTime())))
                .collect(Collectors.toList());
    }

    public List<Record> listRecordsBetweenDates(long userId, String start, String end) throws IOException {
        ensureUserLoaded(userId);
        LocalDate startDate = LocalDate.parse(start, Utils.DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(end, Utils.DATE_FORMATTER);
        return cache.get(userId).stream()
                .filter(r -> {
                    LocalDate rDate = LocalDate.parse(r.getDate(), Utils.DATE_FORMATTER);
                    return !rDate.isBefore(startDate) && !rDate.isAfter(endDate);
                })
                .sorted(Comparator.comparing(r -> Utils.parseToLocalDateTime(r.getDate(), r.getTime())))
                .collect(Collectors.toList());
    }

    public boolean deleteById(int id, long userId) throws IOException {
        ensureUserLoaded(userId);
        lock.lock();
        try {
            List<Record> userRecords = cache.get(userId);
            boolean removed = userRecords.removeIf(r -> r.getId() == id);
            if (removed) {
                writeUserRecords(userId);
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    public boolean updateRecord(int id, long userId, Optional<String> date, Optional<String> time, Optional<Integer> amount, Optional<String> regurg) throws IOException {
        ensureUserLoaded(userId);
        lock.lock();
        try {
            List<Record> userRecords = cache.get(userId);
            Optional<Record> recordOpt = userRecords.stream().filter(r -> r.getId() == id).findFirst();
            if (recordOpt.isEmpty()) {
                return false;
            }
            Record record = recordOpt.get();
            date.ifPresent(record::setDate);
            time.ifPresent(record::setTime);
            amount.ifPresent(record::setAmountMl);
            regurg.ifPresent(record::setRegurg);
            writeUserRecords(userId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void deleteAllForUser(long userId) throws IOException {
        lock.lock();
        try {
            cache.remove(userId);
            Path userPath = Paths.get(getUserFile(userId));
            if (Files.exists(userPath)) {
                Files.delete(userPath);
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean updateRegurg(int id, long userId, String newRegurg) throws IOException {
        return updateRecord(id, userId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(newRegurg));
    }

    public Map<Long, Boolean> getAllDeletePreferences() throws IOException {
        Map<Long, Boolean> prefs = new HashMap<>();
        Path path = Paths.get(PREF_FILE);
        if (!Files.exists(path)) {
            return prefs;
        }
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    try {
                        long id = Long.parseLong(parts[0].trim());
                        boolean val = Boolean.parseBoolean(parts[1].trim());
                        prefs.put(id, val);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid preference line: " + line);
                    }
                }
            }
        }
        return prefs;
    }

    public void saveDeletePreference(long chatId, boolean deleteMessages) throws IOException {
        lock.lock();
        try {
            Map<Long, Boolean> prefs = getAllDeletePreferences();
            prefs.put(chatId, deleteMessages);
            Path tmpPath = Paths.get(PREF_FILE + ".tmp");
            try (BufferedWriter bw = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
                for (Map.Entry<Long, Boolean> entry : prefs.entrySet()) {
                    bw.write(entry.getKey() + ":" + entry.getValue());
                    bw.newLine();
                }
            }
            Files.move(tmpPath, Paths.get(PREF_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            lock.unlock();
        }
    }

    public Map<Long, String> getAllTimeZones() throws IOException {
        Map<Long, String> prefs = new HashMap<>();
        Path path = Paths.get(TZ_FILE);
        if (!Files.exists(path)) {
            return prefs;
        }
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    try {
                        long id = Long.parseLong(parts[0].trim());
                        String val = parts[1].trim();
                        prefs.put(id, val);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid timezone line: " + line);
                    }
                }
            }
        }
        return prefs;
    }

    public void saveTimeZonePreference(long chatId, String zone) throws IOException {
        lock.lock();
        try {
            Map<Long, String> prefs = getAllTimeZones();
            prefs.put(chatId, zone);
            Path tmpPath = Paths.get(TZ_FILE + ".tmp");
            try (BufferedWriter bw = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
                for (Map.Entry<Long, String> entry : prefs.entrySet()) {
                    bw.write(entry.getKey() + ":" + entry.getValue());
                    bw.newLine();
                }
            }
            Files.move(tmpPath, Paths.get(TZ_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            lock.unlock();
        }
    }
}