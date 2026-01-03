package org.example;

import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BabyBot extends TelegramLongPollingBot {
    private final Storage storage = new Storage();
    private final Map<Long, State> userStates = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> userTempData = new ConcurrentHashMap<>();
    private final Map<Long, List<Integer>> lastBotMessageIds = new ConcurrentHashMap<>();
    private final Map<Long, Integer> headerMessageIds = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> userDeletePreferences = new ConcurrentHashMap<>();
    private final Map<Long, ZoneId> userZones = new ConcurrentHashMap<>();

    private enum State {
        IDLE,
        AWAITING_DATE,
        AWAITING_TIME,
        AWAITING_AMOUNT,
        AWAITING_STATS_DATE,
        AWAITING_EDIT_DATE,
        AWAITING_EDIT_TIME,
        AWAITING_EDIT_AMOUNT,
        AWAITING_LIST_DATE,
        AWAITING_DELETE_LIST_DATE,
        AWAITING_HOUR,
        AWAITING_MINUTES,
        AWAITING_EDIT_HOUR,
        AWAITING_EDIT_MINUTES,
        AWAITING_CALENDAR_DAY,
        AWAITING_CALENDAR_MONTH,
        AWAITING_CALENDAR_YEAR,
        AWAITING_DELETE_PREFERENCE,
        AWAITING_STATS_START_DATE,
        AWAITING_STATS_END_DATE
    }

    public BabyBot() {
        super(new DefaultBotOptions());
        try {
            storage.loadCacheIfNeeded();
            loadUserPreferences();
            loadUserTimeZones();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadUserPreferences() throws IOException {
        Map<Long, Boolean> preferences = storage.getAllDeletePreferences();
        userDeletePreferences.putAll(preferences);
    }

    private void loadUserTimeZones() throws IOException {
        Map<Long, String> zones = storage.getAllTimeZones();
        for (Map.Entry<Long, String> entry : zones.entrySet()) {
            try {
                userZones.put(entry.getKey(), ZoneId.of(entry.getValue()));
            } catch (DateTimeException e) {
                e.printStackTrace();
            }
        }
    }

    private void saveUserPreference(long chatId, boolean deleteMessages) throws IOException {
        storage.saveDeletePreference(chatId, deleteMessages);
        userDeletePreferences.put(chatId, deleteMessages);
    }

    @Override
    public String getBotUsername() {
        return "MyBabyFeedBot";
    }

    @Override
    public String getBotToken() {
        return "-----------------------------------------------------------------------------------";
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message message = update.getMessage();
            long chatId = message.getChatId();
            int userMessageId = message.getMessageId();
            String text = message.getText();
            System.out.println("ChatId: " + chatId + ", text: " + text);
            handleUserText(chatId, text, userMessageId);
        } else if (update.hasCallbackQuery()) {
            CallbackQuery callbackQuery = update.getCallbackQuery();
            long chatId = callbackQuery.getMessage().getChatId();
            handleCallback(callbackQuery);
        }
    }

    private void handleUserText(long chatId, String text, int userMessageId) {
        State state = userStates.getOrDefault(chatId, State.IDLE);
        if (text.equals("Пропустить")) {
            handleStateInput(chatId, state, "");
        } else if (text.equals("Назад")) {
            userStates.put(chatId, State.IDLE);
            userTempData.remove(chatId);
            sendMainMenu(chatId);
        } else {
            switch (text) {
                case "/start":
                    sendHeaderIfNeeded(chatId);
                    handleStartCommand(chatId);
                    break;
                default:
                    handleStateInput(chatId, state, text);
                    break;
            }
        }
        DeleteMessage deleteUser = new DeleteMessage();
        deleteUser.setChatId(String.valueOf(chatId));
        deleteUser.setMessageId(userMessageId);
        try {
            execute(deleteUser);
        } catch (TelegramApiException e) {
            // ignore
        }
    }

    private void handleStartCommand(long chatId) {
        if (!userDeletePreferences.containsKey(chatId)) {
            userStates.put(chatId, State.AWAITING_DELETE_PREFERENCE);
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("Привет! Перед началом, хотите ли вы, чтобы бот удалял предыдущие сообщения (оставляя только последнее)?");
            message.setReplyMarkup(createDeletePreferenceInline());
            sendAndAdd(chatId, message);
        } else {
            if (!userZones.containsKey(chatId)) {
                askTimeZonePreference(chatId);
            } else {
                sendWelcomeMessage(chatId);
            }
        }
    }

    private InlineKeyboardMarkup createDeletePreferenceInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton yes = new InlineKeyboardButton("Да, удалять");
        yes.setCallbackData("delete_pref_yes");
        InlineKeyboardButton no = new InlineKeyboardButton("Нет, не удалять");
        no.setCallbackData("delete_pref_no");
        rows.add(List.of(yes, no));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private void sendHeaderIfNeeded(long chatId) {
        if (!headerMessageIds.containsKey(chatId)) {
            SendMessage header = new SendMessage();
            header.setChatId(String.valueOf(chatId));
            header.setText("👶 Бот для отметки кормлений малыша");
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            InlineKeyboardButton channelButton = new InlineKeyboardButton("Наш канал");
            channelButton.setUrl("https://t.me/happy_mom_club");
            markup.setKeyboard(List.of(List.of(channelButton)));
            header.setReplyMarkup(markup);
            try {
                Message sent = execute(header);
                headerMessageIds.put(chatId, sent.getMessageId());
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    private ZoneId getUserZone(long chatId) {
        return userZones.getOrDefault(chatId, Utils.DEFAULT_ZONE);
    }

    private void handleLastFeeding(long chatId) {
        try {
            List<Record> records = storage.listRecords(chatId);
            if (records.isEmpty()) {
                sendMessage(chatId, "Нет записей о кормлениях.");
                return;
            }
            ZoneId zone = getUserZone(chatId);
            Record last = records.get(records.size() - 1);
            LocalDate lastDate = LocalDate.parse(last.getDate(), Utils.DATE_FORMATTER);
            LocalDate today = LocalDate.now(zone);
            LocalDate yesterday = today.minusDays(1);
            String dateDisplay;
            if (lastDate.equals(today)) {
                dateDisplay = "сегодня";
            } else if (lastDate.equals(yesterday)) {
                dateDisplay = "вчера";
            } else {
                dateDisplay = Utils.formatDateRussian(last.getDate());
            }
            LocalDateTime lastTime = Utils.parseToLocalDateTime(last.getDate(), last.getTime());
            LocalDateTime now = LocalDateTime.now(zone);
            long minutesTotal = ChronoUnit.MINUTES.between(lastTime, now);
            long hours = minutesTotal / 60;
            long minutes = minutesTotal % 60;
            String passed = hours + " ч " + minutes + " мин";
            String message;
            if (hours < 3) {
                message = "Совсем недавно покушали 👌";
            } else if (hours < 4) {
                message = "Вот-вот пора кормить 🍼";
            } else {
                message = "Пора кормить малыша 👩‍🍼";
            }
            String text = "Последний раз кормили " + dateDisplay + " в " + last.getTime() + ".\n" +
                    "Прошло: " + passed + "\n" +
                    message;
            sendMessage(chatId, text);
        } catch (IOException e) {
            sendMessage(chatId, "Ошибка при чтении данных.");
        }
    }

    private void sendDeleteMenu(long chatId) {
        userStates.put(chatId, State.AWAITING_DELETE_LIST_DATE);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Нажмите 📅 Сегодня или 👈 Вчера, либо введите дату (ДД.ММ.ГГГГ).\n" +
                "Разделители: : . - / ,");
        message.setReplyMarkup(createDateInline());
        sendWithDelete(chatId, message);
    }

    private void sendWelcomeMessage(long chatId) {
        String welcome = "Привет! 👋\n" +
                "Я помогу вести учёт кормлений малыша 🍼.\n\n" +
                "Зайдите пожалуйста сразу в настройки и задайте часовой пояс, чтобы не путаться).\n\n" +
                "На всякий случай делайте скриншоты записей — бот новый, вдруг сбой.\n" +
                "Вопросы? Пишите @angrymurko.\n\n\n" +
                "Нажмите «➕ Добавить кормление», чтобы начать.";
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(welcome);
        message.setReplyMarkup(getMainInline());
        sendWithDelete(chatId, message);
    }

    private void sendHelp(long chatId) {
        String help = "🌸 Привет, мамочка! Вот как работает наш милый бот для кормлений малыша 🍼:\n\n" +
                "➕ Добавить кормление:\n 1) Выбери дату (сегодня, вчера или другую), \n 2) Время (сейчас или укажи), \n 3) Сколько мл съел малыш, \n 4) Отметь срыгивание, если было (воздушек, переели (срыгнули получается) или нет). \n 4) Готово, запись сохранена! ❤️\n\n" +
                "📊 Статистика:\n Посмотри за сегодня, последние 7 дней или выбери период! \n Тут есть подробная сводка с деталями по каждому дню и кормлению, или общая — сколько раз кормили, всего мл и срыгивания. \n Удобно следить за прогрессом! 📈\n\n" +
                "📜 Список кормлений:\n Выбери дату, увидишь все записи за день. \n Можно:\n ✏️ Отредактировать (дату, время, мл),\n 🗑 Удалить \n 🤢 Отметить срыгивание. \n Всё под рукой! \n\n" +
                "⌛ Давно ли кормили?\n Покажу, когда было последнее кормление, сколько времени прошло и подскажу, пора ли кушать. \n Не нужно считать в уме! ⏰\n\n" +
                "⚙️ Настройки:\n Выбери, удалять ли старые сообщения бота, смени часовой пояс или удали всю историю, если нужно. \nВсё просто! \n\n" +
                "💡 Бот ещё малыш, но старается! \n Делай скриншоты на всякий случай, но если ты сама ничего не удалила, а всё пропало - пиши @angrymurko, я буду рад помочь! 💌";
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(help);
        message.setReplyMarkup(createBackInline());
        sendWithDelete(chatId, message);
    }

    private void startAddFeeding(long chatId) {
        userStates.put(chatId, State.AWAITING_DATE);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Введите дату (ДД.ММ.ГГГГ) или нажмите 📅 Сегодня.\n" +
                "Разделители: : . - / , \nГод можно опустить.");
        message.setReplyMarkup(createDateInline());
        sendWithDelete(chatId, message);
    }

    private void handleSpecialButton(long chatId, State state, String buttonText) {
        Map<String, Object> tempData = userTempData.computeIfAbsent(chatId, k -> new HashMap<>());
        ZoneId zone = getUserZone(chatId);
        switch (state) {
            case AWAITING_DATE, AWAITING_EDIT_DATE, AWAITING_STATS_DATE, AWAITING_LIST_DATE, AWAITING_DELETE_LIST_DATE, AWAITING_STATS_START_DATE, AWAITING_STATS_END_DATE -> {
                String selectedDate = switch (buttonText) {
                    case "📅 Сегодня" -> Utils.getCurrentDate(zone);
                    case "👈 Вчера" -> LocalDate.now(zone).minusDays(1).format(Utils.DATE_FORMATTER);
                    default -> null;
                };
                if (selectedDate != null) {
                    if (state == State.AWAITING_DATE || state == State.AWAITING_EDIT_DATE) {
                        tempData.put("date", selectedDate);
                        userStates.put(chatId, state == State.AWAITING_DATE ? State.AWAITING_TIME : State.AWAITING_EDIT_TIME);
                        if (state == State.AWAITING_DATE) {
                            askForTime(chatId);
                        } else {
                            askForEditTime(chatId);
                        }
                    } else if (state == State.AWAITING_STATS_DATE) {
                        try {
                            showStatsByDate(chatId, selectedDate);
                        } catch (IOException e) {
                            sendMessage(chatId, "Ошибка при чтении данных.");
                        }
                    } else if (state == State.AWAITING_LIST_DATE) {
                        try {
                            showListByDate(chatId, selectedDate, false);
                        } catch (IOException e) {
                            sendMessage(chatId, "Ошибка при чтении данных.");
                        }
                    } else if (state == State.AWAITING_DELETE_LIST_DATE) {
                        try {
                            showListByDate(chatId, selectedDate, true);
                        } catch (IOException e) {
                            sendMessage(chatId, "Ошибка при чтении данных.");
                        }
                    } else if (state == State.AWAITING_STATS_START_DATE) {
                        tempData.put("start_date", selectedDate);
                        userStates.put(chatId, State.AWAITING_STATS_END_DATE);
                        SendMessage msg = new SendMessage();
                        msg.setChatId(String.valueOf(chatId));
                        msg.setText("Введите дату ДО (ДД.ММ.ГГГГ) или выберите.");
                        msg.setReplyMarkup(createDateInline());
                        sendWithDelete(chatId, msg);
                    } else if (state == State.AWAITING_STATS_END_DATE) {
                        tempData.put("end_date", selectedDate);
                        askSummaryType(chatId);
                    }
                }
            }
            case AWAITING_TIME, AWAITING_EDIT_TIME -> {
                if (buttonText.equals("🕒 Сейчас")) {
                    String currentTime = Utils.getCurrentTime(zone);
                    tempData.put("time", currentTime);
                    userStates.put(chatId, state == State.AWAITING_TIME ? State.AWAITING_AMOUNT : State.AWAITING_EDIT_AMOUNT);
                    if (state == State.AWAITING_TIME) {
                        askForAmount(chatId);
                    } else {
                        askForEditAmount(chatId);
                    }
                }
            }
            default -> {}
        }
    }

    private void askForTime(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Нажмите 🕒 Сейчас, введите время (ЧЧ:ММ) или выберите из кнопок.");
        message.setReplyMarkup(createTimeInline(false));
        sendWithDelete(chatId, message);
    }

    private void askForAmount(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Сколько мл съел малыш? 🍼\nВыберите кнопку или введите число.");
        message.setReplyMarkup(createAmountInline(false));
        sendWithDelete(chatId, message);
    }

    private void askForEditDate(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Новая дата (ДД.ММ.ГГГГ), 📅 Сегодня или Пропустить.");
        message.setReplyMarkup(createEditDateInline());
        sendWithDelete(chatId, message);
    }

    private void askForEditTime(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Новое время (ЧЧ:ММ), выберите из кнопок или Пропустить.");
        message.setReplyMarkup(createTimeInline(true));
        sendWithDelete(chatId, message);
    }

    private void askForEditAmount(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Новое количество мл или Пропустить.");
        message.setReplyMarkup(createAmountInline(true));
        sendWithDelete(chatId, message);
    }

    private void sendHourMessage(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите час: ⏰");
        message.setReplyMarkup(createHourInline());
        sendWithDelete(chatId, message);
    }

    private void sendMinutesMessage(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите минуты: ⏱");
        message.setReplyMarkup(createMinutesInline());
        sendWithDelete(chatId, message);
    }

    private void sendDayMessage(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите день: 📅");
        message.setReplyMarkup(createDayInline());
        sendWithDelete(chatId, message);
    }

    private void sendMonthMessage(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите месяц: 🗓");
        message.setReplyMarkup(createMonthInline());
        sendWithDelete(chatId, message);
    }

    private void sendYearMessage(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите год или введите (4 цифры): 📆");
        message.setReplyMarkup(createYearInline());
        sendWithDelete(chatId, message);
    }

    private InlineKeyboardMarkup createEditDateInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton today = new InlineKeyboardButton("📅 Сегодня");
        today.setCallbackData("select_date_today");
        row1.add(today);
        InlineKeyboardButton yesterday = new InlineKeyboardButton("👈 Вчера");
        yesterday.setCallbackData("select_date_yesterday");
        row1.add(yesterday);
        rows.add(row1);
        InlineKeyboardButton skip = new InlineKeyboardButton("⏭ Пропустить");
        skip.setCallbackData("skip");
        rows.add(List.of(skip));
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createAmountInline(boolean withSkip) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton b60 = new InlineKeyboardButton("🍼 60");
        b60.setCallbackData("amount_60");
        row1.add(b60);
        InlineKeyboardButton b90 = new InlineKeyboardButton("🍼 90");
        b90.setCallbackData("amount_90");
        row1.add(b90);
        InlineKeyboardButton b120 = new InlineKeyboardButton("🍼 120");
        b120.setCallbackData("amount_120");
        row1.add(b120);
        rows.add(row1);
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton b150 = new InlineKeyboardButton("🍼 150");
        b150.setCallbackData("amount_150");
        row2.add(b150);
        InlineKeyboardButton b180 = new InlineKeyboardButton("🍼 180");
        b180.setCallbackData("amount_180");
        row2.add(b180);
        InlineKeyboardButton b210 = new InlineKeyboardButton("🍼 210");
        b210.setCallbackData("amount_210");
        row2.add(b210);
        rows.add(row2);
        InlineKeyboardButton b240 = new InlineKeyboardButton("🍼 240");
        b240.setCallbackData("amount_240");
        rows.add(List.of(b240));
        if (withSkip) {
            InlineKeyboardButton skip = new InlineKeyboardButton("⏭ Пропустить");
            skip.setCallbackData("skip");
            rows.add(List.of(skip));
        }
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private void handleStateInput(long chatId, State state, String text) {
        Map<String, Object> tempData = userTempData.computeIfAbsent(chatId, k -> new HashMap<>());
        boolean isSkip = text.isEmpty();
        text = text.replace('.', ':').replace('/', ':').replace('-', ':').replace(',', ':');
        ZoneId zone = getUserZone(chatId);
        if (state == State.AWAITING_DATE || state == State.AWAITING_EDIT_DATE || state == State.AWAITING_STATS_DATE || state == State.AWAITING_LIST_DATE || state == State.AWAITING_DELETE_LIST_DATE || state == State.AWAITING_STATS_START_DATE || state == State.AWAITING_STATS_END_DATE) {
            if (text.matches("\\d{4}")) {
                String day = text.substring(0, 2);
                String month = text.substring(2, 4);
                text = day + ":" + month;
            } else if (text.matches("\\d{6}")) {
                String day = text.substring(0, 2);
                String month = text.substring(2, 4);
                String year = "20" + text.substring(4, 6);
                text = day + ":" + month + ":" + year;
            } else if (text.matches("\\d{8}")) {
                String day = text.substring(0, 2);
                String month = text.substring(2, 4);
                String year = text.substring(4, 8);
                text = day + ":" + month + ":" + year;
            }
        }
        switch (state) {
            case AWAITING_DATE, AWAITING_EDIT_DATE -> {
                if (isSkip && state == State.AWAITING_EDIT_DATE) {
                    userStates.put(chatId, State.AWAITING_EDIT_TIME);
                    askForEditTime(chatId);
                    break;
                }
                if (Utils.isValidDate(text)) {
                    String[] parts = text.split(":");
                    String day = String.format("%02d", Integer.parseInt(parts[0]));
                    String month = String.format("%02d", Integer.parseInt(parts[1]));
                    String year = parts.length == 3 ? parts[2] : String.valueOf(LocalDate.now(zone).getYear());
                    if (year.length() == 2) year = "20" + year;
                    String formattedDate = day + ":" + month + ":" + year;
                    tempData.put("date", formattedDate);
                    userStates.put(chatId, state == State.AWAITING_DATE ? State.AWAITING_TIME : State.AWAITING_EDIT_TIME);
                    if (state == State.AWAITING_DATE) {
                        askForTime(chatId);
                    } else {
                        askForEditTime(chatId);
                    }
                } else {
                    sendMessage(chatId, "Неверная дата. Попробуйте ДД.ММ.ГГГГ.");
                }
            }
            case AWAITING_TIME, AWAITING_EDIT_TIME -> {
                if (isSkip && state == State.AWAITING_EDIT_TIME) {
                    userStates.put(chatId, State.AWAITING_EDIT_AMOUNT);
                    askForEditAmount(chatId);
                    break;
                }
                text = text.replace('.', ':').replace('-', ':');
                String[] parts = text.split(":");
                if (parts.length == 2) {
                    try {
                        int h = Integer.parseInt(parts[0]);
                        int m = Integer.parseInt(parts[1]);
                        if (h >= 0 && h <= 23 && m >= 0 && m <= 59) {
                            String formattedTime = String.format("%02d:%02d", h, m);
                            tempData.put("time", formattedTime);
                            userStates.put(chatId, state == State.AWAITING_TIME ? State.AWAITING_AMOUNT : State.AWAITING_EDIT_AMOUNT);
                            if (state == State.AWAITING_TIME) {
                                askForAmount(chatId);
                            } else {
                                askForEditAmount(chatId);
                            }
                        } else {
                            sendMessage(chatId, "Неверное время (00-23:00-59).");
                        }
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "Формат: ЧЧ:ММ.");
                    }
                } else {
                    sendMessage(chatId, "Формат: ЧЧ:ММ.");
                }
            }
            case AWAITING_HOUR, AWAITING_EDIT_HOUR -> {
                try {
                    int hour = Integer.parseInt(text);
                    if (hour >= 0 && hour <= 23) {
                        tempData.put("hour", String.format("%02d", hour));
                        userStates.put(chatId, state == State.AWAITING_HOUR ? State.AWAITING_MINUTES : State.AWAITING_EDIT_MINUTES);
                        sendMinutesMessage(chatId);
                    } else {
                        sendMessage(chatId, "Час: 00-23.");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Неверный час.");
                }
            }
            case AWAITING_MINUTES, AWAITING_EDIT_MINUTES -> {
                try {
                    int minutes = Integer.parseInt(text);
                    if (List.of(0, 10, 20, 30, 40, 50).contains(minutes)) {
                        String minStr = String.format("%02d", minutes);
                        String hour = (String) tempData.get("hour");
                        String time = hour + ":" + minStr;
                        tempData.put("time", time);
                        userStates.put(chatId, state == State.AWAITING_MINUTES ? State.AWAITING_AMOUNT : State.AWAITING_EDIT_AMOUNT);
                        if (state == State.AWAITING_MINUTES) {
                            askForAmount(chatId);
                        } else {
                            askForEditAmount(chatId);
                        }
                    } else {
                        sendMessage(chatId, "Минуты: 00,10,20,30,40,50.");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Неверные минуты.");
                }
            }
            case AWAITING_AMOUNT -> {
                try {
                    int amount = Integer.parseInt(text);
                    if (amount > 0 && amount <= 2000) {
                        String date = (String) tempData.get("date");
                        String time = (String) tempData.get("time");
                        int id = storage.addRecord(chatId, date, time, amount, "unknown");
                        String confirmation = "✅ Записано: " + Utils.formatDateRussian(date) + " в " + time + " — " + amount + " мл\n\nСрыгнул(а)?";
                        SendMessage message = new SendMessage();
                        message.setChatId(String.valueOf(chatId));
                        message.setText(confirmation);
                        message.setReplyMarkup(createRegurgButtons(id));
                        sendWithDelete(chatId, message);
                        userStates.put(chatId, State.IDLE);
                        userTempData.remove(chatId);
                    } else {
                        sendMessage(chatId, "Количество: 1-2000 мл.");
                    }
                } catch (NumberFormatException | IOException e) {
                    sendMessage(chatId, "Введите число.");
                }
            }
            case AWAITING_STATS_DATE -> {
                if (Utils.isValidDate(text)) {
                    try {
                        showStatsByDate(chatId, text);
                    } catch (IOException e) {
                        sendMessage(chatId, "Ошибка данных.");
                    }
                } else {
                    sendMessage(chatId, "Дата: ДД.ММ.ГГГГ.");
                }
            }
            case AWAITING_LIST_DATE -> {
                if (Utils.isValidDate(text)) {
                    try {
                        showListByDate(chatId, text, false);
                    } catch (IOException e) {
                        sendMessage(chatId, "Ошибка данных.");
                    }
                } else {
                    sendMessage(chatId, "Дата: ДД.ММ.ГГГГ.");
                }
            }
            case AWAITING_DELETE_LIST_DATE -> {
                if (Utils.isValidDate(text)) {
                    try {
                        showListByDate(chatId, text, true);
                    } catch (IOException e) {
                        sendMessage(chatId, "Ошибка данных.");
                    }
                } else {
                    sendMessage(chatId, "Дата: ДД.ММ.ГГГГ.");
                }
            }
            case AWAITING_EDIT_AMOUNT -> {
                try {
                    Integer amount = isSkip ? null : Integer.parseInt(text);
                    if (amount != null && (amount <= 0 || amount > 2000)) {
                        sendMessage(chatId, "Количество: 1-2000.");
                        return;
                    }
                    int id = (Integer) tempData.get("editId");
                    Optional<String> newDate = Optional.ofNullable((String) tempData.get("date"));
                    Optional<String> newTime = Optional.ofNullable((String) tempData.get("time"));
                    Optional<Integer> newAmount = amount == null ? Optional.empty() : Optional.of(amount);
                    boolean updated = storage.updateRecord(id, chatId, newDate, newTime, newAmount, Optional.empty());
                    if (updated) {
                        sendMessage(chatId, "✅ Запись обновлена.");
                    } else {
                        sendMessage(chatId, "Ошибка: запись не найдена.");
                    }
                    userStates.put(chatId, State.IDLE);
                    userTempData.remove(chatId);
                } catch (NumberFormatException | IOException e) {
                    sendMessage(chatId, "Неверное количество.");
                }
            }
            case AWAITING_CALENDAR_DAY -> {
                try {
                    int day = Integer.parseInt(text);
                    if (day >= 1 && day <= 31) {
                        tempData.put("day", String.format("%02d", day));
                        userStates.put(chatId, State.AWAITING_CALENDAR_MONTH);
                        sendMonthMessage(chatId);
                    } else {
                        sendMessage(chatId, "День: 1-31.");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Неверный день.");
                }
            }
            case AWAITING_CALENDAR_MONTH -> {
                try {
                    int month = Integer.parseInt(text);
                    if (month >= 1 && month <= 12) {
                        tempData.put("month", String.format("%02d", month));
                        userStates.put(chatId, State.AWAITING_CALENDAR_YEAR);
                        sendYearMessage(chatId);
                    } else {
                        sendMessage(chatId, "Месяц: 1-12.");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Неверный месяц.");
                }
            }
            case AWAITING_CALENDAR_YEAR -> {
                String yearStr;
                if (text.equals("Текущий")) {
                    yearStr = String.valueOf(LocalDate.now(zone).getYear());
                } else {
                    try {
                        int y = Integer.parseInt(text);
                        if (y >= 2000 && y <= 2100) {
                            yearStr = String.valueOf(y);
                        } else {
                            sendMessage(chatId, "Неверный год.");
                            return;
                        }
                    } catch (NumberFormatException e) {
                        sendMessage(chatId, "Формат года: 4 цифры.");
                        return;
                    }
                }
                String dateStr = tempData.get("day") + ":" + tempData.get("month") + ":" + yearStr;
                if (Utils.isValidDate(dateStr)) {
                    State originalState = (State) tempData.get("originalDateState");
                    handleStateInput(chatId, originalState, dateStr);
                } else {
                    sendMessage(chatId, "Неверная дата (день/месяц).");
                    userStates.put(chatId, State.AWAITING_CALENDAR_DAY);
                    sendDayMessage(chatId);
                }
            }
            case AWAITING_STATS_START_DATE -> {
                if (Utils.isValidDate(text)) {
                    String[] parts = text.split(":");
                    String day = String.format("%02d", Integer.parseInt(parts[0]));
                    String month = String.format("%02d", Integer.parseInt(parts[1]));
                    String year = parts.length == 3 ? parts[2] : String.valueOf(LocalDate.now(zone).getYear());
                    if (year.length() == 2) year = "20" + year;
                    String formattedDate = day + ":" + month + ":" + year;
                    tempData.put("start_date", formattedDate);
                    userStates.put(chatId, State.AWAITING_STATS_END_DATE);
                    SendMessage msg = new SendMessage();
                    msg.setChatId(String.valueOf(chatId));
                    msg.setText("Введите дату ДО (ДД.ММ.ГГГГ) или выберите.");
                    msg.setReplyMarkup(createDateInline());
                    sendWithDelete(chatId, msg);
                } else {
                    sendMessage(chatId, "Неверная дата начала. Попробуйте ДД.ММ.ГГГГ.");
                }
            }
            case AWAITING_STATS_END_DATE -> {
                if (Utils.isValidDate(text)) {
                    String[] parts = text.split(":");
                    String day = String.format("%02d", Integer.parseInt(parts[0]));
                    String month = String.format("%02d", Integer.parseInt(parts[1]));
                    String year = parts.length == 3 ? parts[2] : String.valueOf(LocalDate.now(zone).getYear());
                    if (year.length() == 2) year = "20" + year;
                    String formattedDate = day + ":" + month + ":" + year;
                    tempData.put("end_date", formattedDate);
                    askSummaryType(chatId);
                } else {
                    sendMessage(chatId, "Неверная дата окончания. Попробуйте ДД.ММ.ГГГГ.");
                }
            }
            default -> sendMainMenu(chatId);
        }
    }

    private void askSummaryType(long chatId) {
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("Выберите тип сводки:");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton detailed = new InlineKeyboardButton("Подробная сводка");
        detailed.setCallbackData("summary_detailed");
        InlineKeyboardButton general = new InlineKeyboardButton("Общая сводка");
        general.setCallbackData("summary_general");
        rows.add(List.of(detailed, general));
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        markup.setKeyboard(rows);
        msg.setReplyMarkup(markup);
        sendWithDelete(chatId, msg);
    }

    private void sendStatsMenu(long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton todayButton = new InlineKeyboardButton("📅 Сегодня");
        todayButton.setCallbackData("stats_today");
        rows.add(List.of(todayButton));
        InlineKeyboardButton last7Button = new InlineKeyboardButton("📊 Сводка за последние 7 дней");
        last7Button.setCallbackData("summary_7days");
        rows.add(List.of(last7Button));
        InlineKeyboardButton customButton = new InlineKeyboardButton("📊 Сводка за выбранный период");
        customButton.setCallbackData("stats_custom");
        rows.add(List.of(customButton));
        InlineKeyboardButton chooseDateButton = new InlineKeyboardButton("📆 Выбрать дату");
        chooseDateButton.setCallbackData("stats_choose_date");
        rows.add(List.of(chooseDateButton));
        InlineKeyboardButton backButton = new InlineKeyboardButton("🔙 Назад");
        backButton.setCallbackData("back");
        rows.add(List.of(backButton));
        markup.setKeyboard(rows);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Статистика за:");
        message.setReplyMarkup(markup);
        sendWithDelete(chatId, message);
    }

    private void showStatsByDate(long chatId, String date) throws IOException {
        List<Record> records = storage.listRecordsByDate(chatId, date);
        if (records.isEmpty()) {
            sendMessage(chatId, "Нет записей за " + Utils.formatDateRussian(date) + ".");
            return;
        }
        int count = records.size();
        int totalMl = records.stream().mapToInt(Record::getAmountMl).sum();
        double avgMl = (double) totalMl / count;
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Статистика за ").append(Utils.formatDateRussian(date)).append(":\n");
        sb.append("Кормлений: ").append(count).append("\n");
        sb.append("Всего мл: ").append(totalMl).append("\n");
        sb.append("Среднее: ").append(Math.round(avgMl)).append(" мл\n\n");
        sb.append("Список:\n");
        for (int i = 0; i < records.size(); i++) {
            Record r = records.get(i);
            sb.append(i + 1).append(". ").append(r.getTime()).append(" — ").append(r.getAmountMl()).append(" мл ").append(Utils.regurgToDisplay(r.getRegurg())).append("\n");
        }
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(sb.toString());
        message.setReplyMarkup(createBackInline());
        sendWithDelete(chatId, message);
        userStates.put(chatId, State.IDLE);
    }

    private void showSummaryBetween(long chatId, String start, String end, boolean detailed) throws IOException {
        LocalDate startDate = LocalDate.parse(start, Utils.DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(end, Utils.DATE_FORMATTER);
        if (endDate.isBefore(startDate)) {
            sendMessage(chatId, "Дата окончания раньше начала. Попробуйте снова.");
            return;
        }
        List<Record> records = storage.listRecordsBetweenDates(chatId, start, end);
        if (records.isEmpty()) {
            sendMessage(chatId, "Нет записей за период с " + Utils.formatDateRussian(start) + " по " + Utils.formatDateRussian(end) + ".");
            return;
        }
        Map<LocalDate, List<Record>> groupByDate = records.stream()
                .collect(Collectors.groupingBy(r -> LocalDate.parse(r.getDate(), Utils.DATE_FORMATTER)));
        TreeMap<LocalDate, List<Record>> sortedGroup = new TreeMap<>(groupByDate);
        StringBuilder sb = new StringBuilder("📊 Сводка за период с " + startDate.format(Utils.SHORT_DATE_FORMATTER) + " по " + endDate.format(Utils.SHORT_DATE_FORMATTER) + ":\n\n");
        for (Map.Entry<LocalDate, List<Record>> entry : sortedGroup.entrySet()) {
            LocalDate day = entry.getKey();
            List<Record> dayRecords = entry.getValue();
            dayRecords.sort(Comparator.comparing(r -> LocalTime.parse(r.getTime(), Utils.TIME_FORMATTER)));
            int count = dayRecords.size();
            int totalMl = dayRecords.stream().mapToInt(Record::getAmountMl).sum();
            String dayStr = day.format(Utils.SHORT_DATE_FORMATTER);
            if (detailed) {
                sb.append(dayStr).append(" - ").append(count).append(" кормлений, \n\n Всего за день ").append(totalMl).append(" мл\n");
                for (int i = 0; i < dayRecords.size(); i++) {
                    Record r = dayRecords.get(i);
                    sb.append(i + 1).append(") ").append(r.getTime()).append(" - ").append(r.getAmountMl()).append("мл - ").append(Utils.regurgToDisplay(r.getRegurg())).append("\n");
                }
                sb.append("\n");
            } else {
                long regurgCount = dayRecords.stream().filter(r -> "milk".equals(r.getRegurg())).count();
                sb.append(dayStr).append(" - Кормили ").append(count).append(" раз, всего ").append(totalMl).append(" мл, Срыгнули ").append(regurgCount).append(" раз\n");
            }
        }
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(sb.toString());
        message.setReplyMarkup(createBackInline());
        sendWithDelete(chatId, message);
        userStates.put(chatId, State.IDLE);
        userTempData.remove(chatId);
    }

    private void showListByDate(long chatId, String date, boolean deleteMode) throws IOException {
        List<Record> records = storage.listRecordsByDate(chatId, date);
        if (records.isEmpty()) {
            sendMessage(chatId, "Нет записей за " + Utils.formatDateRussian(date) + ".");
            return;
        }
        StringBuilder sb = new StringBuilder((deleteMode ? "🗑 Список для удаления" : "📜 Список кормлений") + " за " + Utils.formatDateRussian(date) + ":\n\n");
        for (int i = 0; i < records.size(); i++) {
            Record r = records.get(i);
            sb.append(i+1).append(": ").append(r.getTime()).append(" — ").append(r.getAmountMl()).append(" мл ").append(Utils.regurgToDisplay(r.getRegurg())).append("\n");
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            Record r = records.get(i);
            List<InlineKeyboardButton> row = new ArrayList<>();
            InlineKeyboardButton deleteButton = new InlineKeyboardButton("🗑 Удалить" + (i+1));
            deleteButton.setCallbackData("delete:" + r.getId());
            row.add(deleteButton);
            if (!deleteMode) {
                InlineKeyboardButton editButton = new InlineKeyboardButton("✏️ Редактировать №" + (i+1));
                editButton.setCallbackData("edit:" + r.getId());
                InlineKeyboardButton regurgButton = new InlineKeyboardButton("🤢 Срыгивание №" + (i+1));
                regurgButton.setCallbackData("regurg_menu:" + r.getId());
                row.add(editButton);
                row.add(regurgButton);
            }
            rows.add(row);
        }
        markup.setKeyboard(rows);
        SendMessage combinedMsg = new SendMessage();
        combinedMsg.setChatId(String.valueOf(chatId));
        combinedMsg.setText(sb.toString());
        combinedMsg.setReplyMarkup(markup);
        sendWithDelete(chatId, combinedMsg);

        InlineKeyboardMarkup anotherDateMarkup = new InlineKeyboardMarkup();
        InlineKeyboardButton anotherDateButton = new InlineKeyboardButton("📆 Другая дата");
        anotherDateButton.setCallbackData(deleteMode ? "delete_choose_date" : "list_choose_date");
        InlineKeyboardButton backButton = new InlineKeyboardButton("🔙 Назад");
        backButton.setCallbackData("back");
        anotherDateMarkup.setKeyboard(List.of(List.of(anotherDateButton, backButton)));
        SendMessage anotherMsg = new SendMessage();
        anotherMsg.setChatId(String.valueOf(chatId));
        anotherMsg.setText("Посмотреть за другую дату?");
        anotherMsg.setReplyMarkup(anotherDateMarkup);
        sendAndAdd(chatId, anotherMsg);
    }

    private InlineKeyboardMarkup createRegurgButtons(int id) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
//        InlineKeyboardButton air = new InlineKeyboardButton("💨 Воздушек");
//        air.setCallbackData("regurg_air:" + id);
        InlineKeyboardButton milk = new InlineKeyboardButton("Да");
        milk.setCallbackData("regurg_milk:" + id);
        InlineKeyboardButton no = new InlineKeyboardButton("Нет");
        no.setCallbackData("regurg_no:" + id);
        rows.add(List.of(milk, no));
        markup.setKeyboard(rows);
        return markup;
    }

    private void handleCallback(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        int messageId = callbackQuery.getMessage().getMessageId();
        State state = userStates.getOrDefault(chatId, State.IDLE);
        if (data.equals("delete_pref_yes") || data.equals("delete_pref_no")) {
            boolean yes = data.endsWith("yes");
            try {
                saveUserPreference(chatId, yes);
                if (!userZones.containsKey(chatId)) {
                    askTimeZonePreference(chatId);
                } else {
                    userStates.put(chatId, State.IDLE);
                    sendWelcomeMessage(chatId);
                }
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка сохранения настройки.");
            }
        } else if (data.startsWith("regurg_air:")) {
            int id = Integer.parseInt(data.split(":", 2)[1]);
            try {
                boolean updated = storage.updateRegurg(id, chatId, "air");
                if (updated) {
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId));
                    edit.setMessageId(messageId);
                    //edit.setText("💨 Воздушек отмечена.");
                    edit.setReplyMarkup(createMainMenuInline());
                    execute(edit);
                } else {
                    sendMessage(chatId, "Ошибка обновления.");
                }
            } catch (IOException | TelegramApiException e) {
                sendMessage(chatId, "Ошибка.");
            }
        } else if (data.startsWith("regurg_milk:")) {
            int id = Integer.parseInt(data.split(":", 2)[1]);
            try {
                boolean updated = storage.updateRegurg(id, chatId, "milk");
                if (updated) {
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId));
                    edit.setMessageId(messageId);
                    edit.setText("🤢 Переели.");
                    edit.setReplyMarkup(createMainMenuInline());
                    execute(edit);
                } else {
                    sendMessage(chatId, "Ошибка обновления.");
                }
            } catch (IOException | TelegramApiException e) {
                sendMessage(chatId, "Ошибка.");
            }
        } else if (data.startsWith("regurg_no:")) {
            int id = Integer.parseInt(data.split(":", 2)[1]);
            try {
                boolean updated = storage.updateRegurg(id, chatId, "no");
                if (updated) {
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId));
                    edit.setMessageId(messageId);
                    edit.setText("❌ Без срыгивания.");
                    edit.setReplyMarkup(createMainMenuInline());
                    execute(edit);
                } else {
                    sendMessage(chatId, "Ошибка обновления.");
                }
            } catch (IOException | TelegramApiException e) {
                sendMessage(chatId, "Ошибка.");
            }
        } else if (data.startsWith("delete:")) {
            int id = Integer.parseInt(data.split(":", 2)[1]);
            try {
                boolean deleted = storage.deleteById(id, chatId);
                if (deleted) {
                    editMessage(chatId, messageId, "🗑 Запись удалена.");
                } else {
                    sendMessage(chatId, "Ошибка: запись не найдена.");
                }
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка.");
            }
        } else if (data.startsWith("edit:")) {
            int id = Integer.parseInt(data.split(":", 2)[1]);
            userTempData.computeIfAbsent(chatId, k -> new HashMap<>()).put("editId", id);
            userStates.put(chatId, State.AWAITING_EDIT_DATE);
            askForEditDate(chatId);
        } else if (data.startsWith("regurg_menu:")) {
            int id = Integer.parseInt(data.split(":", 2)[1]);
            InlineKeyboardMarkup markup = createRegurgButtons(id);
            editMessageMarkup(chatId, messageId, markup);
        } else if (data.equals("stats_today")) {
            ZoneId zone = getUserZone(chatId);
            try {
                showStatsByDate(chatId, Utils.getCurrentDate(zone));
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка.");
            }
        } else if (data.equals("summary_7days")) {
            ZoneId zone = getUserZone(chatId);
            String today = Utils.getCurrentDate(zone);
            String sevenDaysAgo = LocalDate.now(zone).minusDays(6).format(Utils.DATE_FORMATTER);
            userTempData.computeIfAbsent(chatId, k -> new HashMap<>()).put("start_date", sevenDaysAgo);
            userTempData.get(chatId).put("end_date", today);
            askSummaryType(chatId);
        } else if (data.equals("stats_custom")) {
            userStates.put(chatId, State.AWAITING_STATS_START_DATE);
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText("Введите дату ОТ (ДД.ММ.ГГГГ) или выберите.");
            msg.setReplyMarkup(createDateInline());
            sendWithDelete(chatId, msg);
        } else if (data.equals("stats_choose_date")) {
            userStates.put(chatId, State.AWAITING_STATS_DATE);
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText("Дата для статистики (ДД.ММ.ГГГГ) или 📅 Сегодня.");
            msg.setReplyMarkup(createDateInline());
            sendWithDelete(chatId, msg);
        } else if (data.equals("list_choose_date")) {
            userStates.put(chatId, State.AWAITING_LIST_DATE);
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText("Дата для списка (ДД.ММ.ГГГГ).");
            msg.setReplyMarkup(createDateInline());
            sendWithDelete(chatId, msg);
        } else if (data.equals("delete_choose_date")) {
            userStates.put(chatId, State.AWAITING_DELETE_LIST_DATE);
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText("Дата для удаления (ДД.ММ.ГГГГ).");
            msg.setReplyMarkup(createDateInline());
            sendWithDelete(chatId, msg);
        } else if (data.equals("back")) {
            userStates.put(chatId, State.IDLE);
            userTempData.remove(chatId);
            sendMainMenu(chatId);
        } else if (data.equals("add_feeding")) {
            startAddFeeding(chatId);
        } else if (data.equals("stats")) {
            sendStatsMenu(chatId);
        } else if (data.equals("list_feedings")) {
            ZoneId zone = getUserZone(chatId);
            try {
                showListByDate(chatId, Utils.getCurrentDate(zone), false);
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка данных.");
            }
        } else if (data.equals("delete_record")) {
            sendDeleteMenu(chatId);
        } else if (data.equals("help")) {
            sendHelp(chatId);
        } else if (data.equals("main_menu")) {
            sendMainMenu(chatId);
        } else if (data.equals("last_feeding")) {
            handleLastFeeding(chatId);
        } else if (data.equals("settings")) {
            sendSettingsMenu(chatId);
        } else if (data.equals("set_delete_yes")) {
            try {
                saveUserPreference(chatId, true);
                sendSettingsMenu(chatId);
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка сохранения.");
            }
        } else if (data.equals("set_delete_no")) {
            try {
                saveUserPreference(chatId, false);
                sendSettingsMenu(chatId);
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка сохранения.");
            }
        } else if (data.equals("delete_all")) {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(chatId));
            msg.setText("Вы уверены, что хотите удалить всю историю кормлений?");
            InlineKeyboardMarkup confirmMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> confirmRows = new ArrayList<>();
            InlineKeyboardButton confirmYes = new InlineKeyboardButton("Да");
            confirmYes.setCallbackData("delete_all_confirm");
            InlineKeyboardButton confirmNo = new InlineKeyboardButton("Нет");
            confirmNo.setCallbackData("back");
            confirmRows.add(List.of(confirmYes, confirmNo));
            confirmMarkup.setKeyboard(confirmRows);
            msg.setReplyMarkup(confirmMarkup);
            sendWithDelete(chatId, msg);
        } else if (data.equals("delete_all_confirm")) {
            try {
                storage.deleteAllForUser(chatId);
                sendMessage(chatId, "Вся история удалена.");
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка удаления.");
            }
        } else if (data.equals("summary_detailed")) {
            Map<String, Object> temp = userTempData.get(chatId);
            String start = (String) temp.get("start_date");
            String end = (String) temp.get("end_date");
            try {
                showSummaryBetween(chatId, start, end, true);
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка.");
            }
        } else if (data.equals("summary_general")) {
            Map<String, Object> temp = userTempData.get(chatId);
            String start = (String) temp.get("start_date");
            String end = (String) temp.get("end_date");
            try {
                showSummaryBetween(chatId, start, end, false);
            } catch (IOException e) {
                sendMessage(chatId, "Ошибка.");
            }
        } else if (data.startsWith("select_date_")) {
            String button = switch (data) {
                case "select_date_today" -> "📅 Сегодня";
                case "select_date_yesterday" -> "👈 Вчера";
                default -> "";
            };
            handleSpecialButton(chatId, state, button);
        } else if (data.equals("calendar")) {
            if (List.of(State.AWAITING_DATE, State.AWAITING_EDIT_DATE, State.AWAITING_STATS_DATE, State.AWAITING_LIST_DATE, State.AWAITING_DELETE_LIST_DATE, State.AWAITING_STATS_START_DATE, State.AWAITING_STATS_END_DATE).contains(state)) {
                userTempData.computeIfAbsent(chatId, k -> new HashMap<>()).put("originalDateState", state);
                userStates.put(chatId, State.AWAITING_CALENDAR_DAY);
                sendDayMessage(chatId);
            }
        } else if (data.startsWith("day_")) {
            String dayStr = data.substring(4);
            handleStateInput(chatId, state, dayStr);
        } else if (data.startsWith("month_")) {
            String monthStr = data.substring(6);
            handleStateInput(chatId, state, monthStr);
        } else if (data.equals("year_current")) {
            handleStateInput(chatId, state, "Текущий");
        } else if (data.startsWith("select_time_")) {
            String button = switch (data) {
                case "select_time_now" -> "🕒 Сейчас";
                default -> "";
            };
            handleSpecialButton(chatId, state, button);
        } else if (data.equals("time_manual")) {
            sendMessage(chatId, "Введите ЧЧ:ММ.");
        } else if (data.equals("time_select")) {
            if (state == State.AWAITING_TIME) {
                userStates.put(chatId, State.AWAITING_HOUR);
                sendHourMessage(chatId);
            } else if (state == State.AWAITING_EDIT_TIME) {
                userStates.put(chatId, State.AWAITING_EDIT_HOUR);
                sendHourMessage(chatId);
            }
        } else if (data.startsWith("hour_")) {
            String hourStr = data.substring(5);
            handleStateInput(chatId, state, hourStr);
        } else if (data.startsWith("min_")) {
            String minStr = data.substring(4);
            handleStateInput(chatId, state, minStr);
        } else if (data.startsWith("amount_")) {
            String amountStr = data.substring(7);
            handleStateInput(chatId, state, amountStr);
        } else if (data.equals("skip")) {
            handleStateInput(chatId, state, "");
        } else if (data.startsWith("timezone_")) {
            String zoneStr = data.substring(9);
            try {
                ZoneId zoneId = ZoneId.of(zoneStr);
                storage.saveTimeZonePreference(chatId, zoneStr);
                userZones.put(chatId, zoneId);
                if (userTempData.containsKey(chatId) && userTempData.get(chatId).containsKey("from_settings")) {
                    userTempData.remove(chatId);
                    sendSettingsMenu(chatId);
                } else {
                    userStates.put(chatId, State.IDLE);
                    sendWelcomeMessage(chatId);
                }
            } catch (DateTimeException | IOException e) {
                sendMessage(chatId, "Ошибка установки часового пояса.");
            }
        } else if (data.equals("change_timezone")) {
            userTempData.computeIfAbsent(chatId, k -> new HashMap<>()).put("from_settings", true);
            askTimeZonePreference(chatId);
        }
        try {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackQuery.getId());
            execute(answer);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void askTimeZonePreference(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Выберите ваш часовой пояс. Если вашего нет в списке, напишите @angrymurko.");
        message.setReplyMarkup(createTimeZonePreferenceInline());
        sendWithDelete(chatId, message);
    }

    private InlineKeyboardMarkup createTimeZonePreferenceInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        addTimeZoneButton(rows, "Калининград (UTC+2)", "Europe/Kaliningrad");
        addTimeZoneButton(rows, "Москва (UTC+3)", "Europe/Moscow");
        addTimeZoneButton(rows, "Самара (UTC+4)", "Europe/Samara");
        addTimeZoneButton(rows, "Екатеринбург (UTC+5)", "Asia/Yekaterinburg");
        addTimeZoneButton(rows, "Омск (UTC+6)", "Asia/Omsk");
        addTimeZoneButton(rows, "Красноярск (UTC+7)", "Asia/Krasnoyarsk");
        addTimeZoneButton(rows, "Иркутск (UTC+8)", "Asia/Irkutsk");
        addTimeZoneButton(rows, "Якутск (UTC+9)", "Asia/Yakutsk");
        addTimeZoneButton(rows, "Владивосток (UTC+10)", "Asia/Vladivostok");
        addTimeZoneButton(rows, "Магадан (UTC+11)", "Asia/Magadan");
        addTimeZoneButton(rows, "Камчатка (UTC+12)", "Asia/Kamchatka");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private void addTimeZoneButton(List<List<InlineKeyboardButton>> rows, String text, String zone) {
        InlineKeyboardButton button = new InlineKeyboardButton(text);
        button.setCallbackData("timezone_" + zone);
        rows.add(List.of(button));
    }

    private void sendSettingsMenu(long chatId) {
        boolean currentDelete = userDeletePreferences.getOrDefault(chatId, true);
        String currentZone = getUserZone(chatId).getId();
        String text = "Настройки:\nУдалять предыдущие сообщения: " + (currentDelete ? "Да" : "Нет") +
                "\nЧасовой пояс: " + currentZone;
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton yes = new InlineKeyboardButton("Да, удалять");
        yes.setCallbackData("set_delete_yes");
        InlineKeyboardButton no = new InlineKeyboardButton("Нет, не удалять");
        no.setCallbackData("set_delete_no");
        rows.add(List.of(yes, no));
        InlineKeyboardButton changeTz = new InlineKeyboardButton("Изменить часовой пояс");
        changeTz.setCallbackData("change_timezone");
        rows.add(List.of(changeTz));
        InlineKeyboardButton deleteAll = new InlineKeyboardButton("🗑 Удалить всю историю");
        deleteAll.setCallbackData("delete_all");
        rows.add(List.of(deleteAll));
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        markup.setKeyboard(rows);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(markup);
        sendWithDelete(chatId, message);
    }

    private void editMessage(long chatId, int messageId, String newText) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText(newText);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void editMessageMarkup(long chatId, int messageId, InlineKeyboardMarkup markup) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("Срыгнули? 🤢");
        edit.setReplyMarkup(markup);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(createBackInline());
        sendWithDelete(chatId, message);
    }

    private void sendMainMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Главное меню:");
        message.setReplyMarkup(getMainInline());
        sendWithDelete(chatId, message);
        userStates.put(chatId, State.IDLE);
    }

    private InlineKeyboardMarkup getMainInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton add = new InlineKeyboardButton("➕ Добавить кормление");
        add.setCallbackData("add_feeding");
        InlineKeyboardButton last = new InlineKeyboardButton("⌛ Давно ли кормили?");
        last.setCallbackData("last_feeding");
        rows.add(List.of(add, last));
        InlineKeyboardButton stats = new InlineKeyboardButton("📊 Статистика");
        stats.setCallbackData("stats");
        rows.add(List.of(stats));
        InlineKeyboardButton list = new InlineKeyboardButton("📜 Список кормлений");
        list.setCallbackData("list_feedings");
        rows.add(List.of(list));
//        InlineKeyboardButton delete = new InlineKeyboardButton("🗑 Удалить запись");
//        delete.setCallbackData("delete_record");
//        rows.add(List.of(delete));
        InlineKeyboardButton help = new InlineKeyboardButton("ℹ️ Помощь");
        help.setCallbackData("help");
        InlineKeyboardButton settings = new InlineKeyboardButton("⚙️ Настройки");
        settings.setCallbackData("settings");
        rows.add(List.of(help, settings));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createDateInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton today = new InlineKeyboardButton("📅 Сегодня");
        today.setCallbackData("select_date_today");
        row1.add(today);
        InlineKeyboardButton yesterday = new InlineKeyboardButton("👈 Вчера");
        yesterday.setCallbackData("select_date_yesterday");
        row1.add(yesterday);
        rows.add(row1);
        InlineKeyboardButton cal = new InlineKeyboardButton("🗓 Календарь");
        cal.setCallbackData("calendar");
        rows.add(List.of(cal));
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createTimeInline(boolean withSkip) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton now = new InlineKeyboardButton("🕒 Сейчас");
        now.setCallbackData("select_time_now");
        InlineKeyboardButton select = new InlineKeyboardButton("⏰ Выбрать время");
        select.setCallbackData("time_select");
        rows.add(List.of(now, select));
        if (withSkip) {
            InlineKeyboardButton skip = new InlineKeyboardButton("⏭ Пропустить");
            skip.setCallbackData("skip");
            rows.add(List.of(skip));
        }
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createHourInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 0; i < 24; i += 6) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (int j = 0; j < 6; j++) {
                if (i + j < 24) {
                    InlineKeyboardButton button = new InlineKeyboardButton("⏰ " + String.format("%02d", i + j));
                    button.setCallbackData("hour_" + String.format("%02d", i + j));
                    row.add(button);
                }
            }
            rows.add(row);
        }
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createMinutesInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int min : List.of(0, 10, 20, 30, 40, 50)) {
            InlineKeyboardButton button = new InlineKeyboardButton("⏱ " + String.format("%02d", min));
            button.setCallbackData("min_" + String.format("%02d", min));
            row.add(button);
        }
        rows.add(row);
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createDayInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 1; i <= 31; i += 5) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                if (i + j <= 31) {
                    InlineKeyboardButton button = new InlineKeyboardButton("📅 " + (i + j));
                    button.setCallbackData("day_" + (i + j));
                    row.add(button);
                }
            }
            rows.add(row);
        }
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createMonthInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i += 4) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (int j = 0; j < 4; j++) {
                if (i + j <= 12) {
                    InlineKeyboardButton button = new InlineKeyboardButton("🗓 " + (i + j));
                    button.setCallbackData("month_" + (i + j));
                    row.add(button);
                }
            }
            rows.add(row);
        }
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createYearInline() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton current = new InlineKeyboardButton("📆 Текущий");
        current.setCallbackData("year_current");
        rows.add(List.of(current));
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        rows.add(List.of(back));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createBackInline() {
        InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
        back.setCallbackData("back");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(back)));
        return markup;
    }

    private InlineKeyboardMarkup createMainMenuInline() {
        InlineKeyboardButton mainMenu = new InlineKeyboardButton("🏠 Главное меню");
        mainMenu.setCallbackData("back");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(mainMenu)));
        return markup;
    }

    private void clearPreviousMessages(long chatId) {
        List<Integer> lastIds = lastBotMessageIds.getOrDefault(chatId, new ArrayList<>());
        Integer headerId = headerMessageIds.get(chatId);
        for (int id : lastIds) {
            if (headerId != null && id == headerId) continue;
            DeleteMessage del = new DeleteMessage();
            del.setChatId(String.valueOf(chatId));
            del.setMessageId(id);
            try {
                execute(del);
            } catch (TelegramApiException e) {
                // ignore
            }
        }
        lastBotMessageIds.put(chatId, new ArrayList<>());
    }

    private Message sendAndAdd(long chatId, SendMessage sendMessage) {
        try {
            Message executed = execute(sendMessage);
            lastBotMessageIds.computeIfAbsent(chatId, k -> new ArrayList<>()).add(executed.getMessageId());
            return executed;
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Message sendWithDelete(long chatId, SendMessage sendMessage) {
        Boolean deletePref = userDeletePreferences.getOrDefault(chatId, true);
        if (deletePref) {
            clearPreviousMessages(chatId);
        }
        return sendAndAdd(chatId, sendMessage);
    }
}








