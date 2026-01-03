package org.example;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class Utils {
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd:MM:yyyy");
    public static final DateTimeFormatter SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter CREATED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");

    public static boolean isValidDate(String dateStr) {
        String[] parts = dateStr.split(":");
        if (parts.length == 2) {
            dateStr += ":" + LocalDate.now(DEFAULT_ZONE).getYear();
        } else if (parts.length != 3) {
            return false;
        }
        try {
            LocalDate.parse(dateStr, DATE_FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static boolean isValidTime(String timeStr) {
        try {
            LocalTime.parse(timeStr, TIME_FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static LocalDateTime parseToLocalDateTime(String date, String time) {
        LocalDate localDate = LocalDate.parse(date, DATE_FORMATTER);
        LocalTime localTime = LocalTime.parse(time, TIME_FORMATTER);
        return LocalDateTime.of(localDate, localTime);
    }

    public static String getCurrentDate(ZoneId zoneId) {
        return LocalDate.now(zoneId).format(DATE_FORMATTER);
    }

    public static String getCurrentTime(ZoneId zoneId) {
        return LocalTime.now(zoneId).format(TIME_FORMATTER);
    }

    public static String getCurrentCreatedAt() {
        return LocalDateTime.now(DEFAULT_ZONE).format(CREATED_AT_FORMATTER);
    }

    public static String regurgToDisplay(String regurg) {
        return switch (regurg) {
            case "air" -> "Воздушек";
            case "milk" -> "срыгнули";
            case "no" -> "не срыгнули";
            default -> "не указано";
        };
    }

    public static String formatDateRussian(String dateStr) {
        String[] parts = dateStr.split(":");
        int day = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int year = Integer.parseInt(parts[2]);
        String[] months = {"", "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
        return day + " " + months[month] + " " + year + " года";
    }
}