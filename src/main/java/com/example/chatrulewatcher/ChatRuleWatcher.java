package com.example.chatrulewatcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Server-side chat rule watcher. No client mod is required. */
public final class ChatRuleWatcher implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("chatrulewatcher.json");
    private static final Map<UUID, RepeatState> REPEATS = new ConcurrentHashMap<>();
    private static final Set<UUID> HELPER_UUIDS = new HashSet<>();
    private static final Set<String> HELPER_NAMES = new HashSet<>();
    private static boolean NOTIFY_OPERATORS = true;

    private static final Pattern URL_OR_IP = Pattern.compile("(?i)(https?://|www\\.|discord\\.gg/|t\\.me/|[a-z0-9-]+\\.(com|net|org|ru|gg|me)\\b|\\b(?:\\d{1,3}\\.){3}\\d{1,3}:?\\d{0,5}\\b)");
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final String[] INSULTS = {
        "идиот", "кретин", "мразь", "урод", "дебил", "ничтожество", "сука", "тварь",
        "мудак", "гнида", "пидор", "пидорас", "еблан", "чмо", "гондон", "залупа"
    };
    private static final String[] RELATIVES = {
        "мать", "мама", "мамка", "мамку", "мамой", "отец", "папа", "папка", "батя",
        "родители", "родных", "сестра", "брат", "бабушка", "дедушка", "семья"
    };
    private static final String[] POLITICS = {
        "политика", "политический", "выборы", "президент", "война", "национальность",
        "нацист", "фашист", "расист", "нация", "народ", "религия", "террорист"
    };
    private static final String[] SERVER_CRITICISM = {
        "сервер говно", "сервер плохой", "плохой сервер", "админы говно", "администрация говно",
        "админ тупой", "модеры тупые", "модерация говно", "худший сервер",
        "админы бесполезны", "администрация бесполезна"
    };
    private static final String[] IMPERSONATION = {
        "я админ", "я администратор", "я модератор", "я владелец", "я создатель сервера",
        "я из администрации", "я helper", "я staff", "я оператор", "я оп"
    };
    private static final String[] PROVOCATION = {
        "нарушай правила", "обойди правила", "ломай правила", "давайте заспамим",
        "спровоцируй", "получи мут", "сделай так чтобы забанили", "обойди мут", "обойди бан"
    };
    private static final String[] BEGGING = {
        "дай денег", "дайте денег", "дай ресурсы", "дай вещи", "подари", "подарите",
        "дай донат", "купи мне", "выдай мне", "можно бесплатно", "дай ранг", "дай привилегию",
        "скинь денег", "пожалуйста дай"
    };

    @Override
    public void onInitialize() {
        loadConfig();
        ServerMessageEvents.CHAT.register((signedMessage, sender, params) -> {
            if (sender == null || signedMessage == null) return;
            String raw = signedMessage.getContent().getString();
            String normalized = normalize(raw);
            if (normalized.isBlank()) return;

            String rule = detectRule(sender.getUuid(), raw, normalized, signedMessage.getTimestamp());
            if (rule != null) notifyHelpers(sender.getServer(), sender, rule, raw);
        });
    }

    private static void loadConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (!Files.exists(CONFIG_PATH)) {
                JsonObject defaults = new JsonObject();
                defaults.addProperty("notifyOperators", true);
                defaults.add("helperUuids", new JsonArray());
                defaults.add("helperNames", new JsonArray());
                Files.writeString(CONFIG_PATH, GSON.toJson(defaults), StandardCharsets.UTF_8);
            }
            JsonObject config = GSON.fromJson(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), JsonObject.class);
            if (config == null) return;
            if (config.has("notifyOperators")) NOTIFY_OPERATORS = config.get("notifyOperators").getAsBoolean();
            readStrings(config.getAsJsonArray("helperUuids"), value -> {
                try { HELPER_UUIDS.add(UUID.fromString(value)); } catch (IllegalArgumentException ignored) { }
            });
            readStrings(config.getAsJsonArray("helperNames"), value -> HELPER_NAMES.add(value.toLowerCase(Locale.ROOT)));
        } catch (IOException | RuntimeException exception) {
            System.err.println("[ChatRuleWatcher] Could not load config: " + exception.getMessage());
        }
    }

    private static void readStrings(JsonArray array, java.util.function.Consumer<String> consumer) {
        if (array == null) return;
        array.forEach(element -> {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) consumer.accept(element.getAsString().trim());
        });
    }

    private static String detectRule(UUID sender, String raw, String normalized, Instant now) {
        if (isCapsViolation(raw)) return "3.2 — Более 70% CAPS";
        if (containsPhrase(normalized, IMPERSONATION)) return "3.7 — Выдавать себя за администрацию";
        if (URL_OR_IP.matcher(raw).find()) return "3.5 — Возможная реклама другого проекта";
        if (containsPhrase(normalized, SERVER_CRITICISM)) return "3.6 — Критика сервера/администрации";
        if (containsPhrase(normalized, PROVOCATION)) return "3.9 — Провокация на нарушение правил";
        if (containsAnyWord(normalized, POLITICS)) return "3.8 — Возможная политическая тема";
        if (containsAnyWord(normalized, RELATIVES)) return "3.3 — Упоминание родных";
        if (containsAnyWord(normalized, INSULTS)) return "3.1 — Оскорбление игроков";
        if (containsPhrase(normalized, BEGGING)) return "3.10 — Попрошайничество";
        if (isSpam(sender, normalized, now)) return "3.4 — Флуд или спам";
        return null;
    }

    private static boolean isCapsViolation(String raw) {
        int letters = 0, uppercase = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) uppercase++;
            }
        }
        return letters >= 8 && uppercase * 100.0 / letters > 70.0;
    }

    private static boolean containsAnyWord(String text, String[] words) {
        for (String word : words) if (containsWord(text, word)) return true;
        return false;
    }

    private static boolean containsPhrase(String text, String[] phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])", Pattern.UNICODE_CHARACTER_CLASS).matcher(text).find();
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("§[0-9a-fk-or]", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean isSpam(UUID sender, String message, Instant now) {
        long current = now.toEpochMilli();
        RepeatState state = REPEATS.computeIfAbsent(sender, key -> new RepeatState());
        synchronized (state) {
            while (!state.times.isEmpty() && current - state.times.peekFirst() > 8000) state.times.removeFirst();
            if (message.equals(state.lastMessage)) state.sameCount++; else state.sameCount = 1;
            state.lastMessage = message;
            state.times.addLast(current);
            while (state.times.size() > 10) state.times.removeFirst();
            return state.sameCount == 3 || state.times.size() == 6;
        }
    }

    private static void notifyHelpers(MinecraftServer server, ServerPlayerEntity offender, String rule, String message) {
        String excerpt = message.replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 90) excerpt = excerpt.substring(0, 87) + "...";
        Text alert = Text.literal("§c[МОДЕРАЦИЯ] §f" + offender.getName().getString() + " §7— §e" + rule
            + "\n§8Сообщение: §7" + excerpt);

        for (ServerPlayerEntity online : server.getPlayerManager().getPlayerList()) {
            boolean configuredHelper = HELPER_UUIDS.contains(online.getUuid())
                || HELPER_NAMES.contains(online.getGameProfile().getName().toLowerCase(Locale.ROOT));
            if (configuredHelper || (NOTIFY_OPERATORS && online.hasPermissionLevel(2))) {
                online.sendMessage(alert, false);
            }
        }
    }

    private static final class RepeatState {
        private String lastMessage = "";
        private int sameCount = 0;
        private final Deque<Long> times = new ArrayDeque<>();
    }
}
