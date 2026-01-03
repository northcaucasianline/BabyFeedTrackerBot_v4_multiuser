package org.example;

import java.util.Objects;

public class Record {
    private int id;
    private long userId;
    private String date; // dd:MM:yyyy
    private String time; // HH:mm
    private int amountMl;
    private String regurg; // unknown/air/milk/no
    private String createdAt; // yyyy-MM-dd HH:mm

    public Record(int id, long userId, String date, String time, int amountMl, String regurg, String createdAt) {
        this.id = id;
        this.userId = userId;
        this.date = date;
        this.time = time;
        this.amountMl = amountMl;
        this.regurg = regurg;
        this.createdAt = createdAt;
    }

    public int getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public int getAmountMl() {
        return amountMl;
    }

    public void setAmountMl(int amountMl) {
        this.amountMl = amountMl;
    }

    public String getRegurg() {
        return regurg;
    }

    public void setRegurg(String regurg) {
        this.regurg = regurg;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Record record = (Record) o;
        return id == record.id && userId == record.userId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId);
    }
}