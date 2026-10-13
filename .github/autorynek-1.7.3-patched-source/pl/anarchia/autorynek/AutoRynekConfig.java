package pl.anarchia.autorynek;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Ustawienia moda zapisywane w pliku config/anarchia_autorynek.json.
 * Zapis następuje przy każdej zmianie w menu oraz przy zamykaniu gry.
 */
public final class AutoRynekConfig {
    public static final String OLD_PRICE_REGEX =
            "(?i)(?:cena|koszt|price)[^0-9]{0,8}([0-9](?:[0-9 .,\\u00a0]*[0-9])?)\\s*([km](?!\\p{L}))?";
    public static final String DEFAULT_PRICE_REGEX =
            "(?i)(?:cena|koszt|kosztuje|price|za|oferta)[^0-9]{0,14}([0-9](?:[0-9 .,\\u00a0]*[0-9])?)\\s*([km](?!\\p{L}))?";
    public static final int CONFIG_VERSION = 3;
    public static final int[] SPEED_REFRESH = {2, 4, 10};
    public static final String[] SPEED_NAMES = {"MEGA", "SZYBKO", "BEZPIECZNIE"};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static AutoRynekConfig instance = new AutoRynekConfig();

    /** Pojedynczy przedmiot eventowy lub zestaw do kupowania. */
    public static final class Entry {
        /** Fragment nazwy przedmiotu (bez kolorów i polskich znaków nie ma znaczenia) albo "id:minecraft:totem_of_undying". */
        public String name = "";
        /** Maksymalna cena; 0 = użyj domyślnej ceny maksymalnej. */
        public double maxPrice = 0;
        /** "EVENT" albo "ZESTAW" – tylko etykieta w menu. */
        public String category = "EVENT";

        public Entry() {
        }

        public Entry(String name, double maxPrice, String category) {
            this.name = name;
            this.maxPrice = maxPrice;
            this.category = category;
        }
    }

    // --- Ustawienia widoczne w menu ---
    public int configVersion = 0;
    public boolean enabled = false;
    /** Tryb snajper: szybsze odświeżanie rynku i krótsze przerwy. */
    public boolean sniper = true;
    /** Tempo snajpera: 0 = MEGA (co 2 ticki), 1 = SZYBKO (co 4), 2 = BEZPIECZNIE (co 10). */
    public int speed = 0;
    /** Tylko pierwsza (najnowsza) strona rynku - bez przechodzenia dalej. */
    public boolean firstPageOnly = true;
    /**
     * Odświeżanie rynku siekierką: zamiast zamykać i ponownie otwierać rynek, mod w otwartym rynku
     * (zakładka "Najnowsze") klika dwa razy PRAWYM w siekierkę (przycisk odświeżania).
     */
    public boolean axeRefresh = true;
    /** Przerwa (w tickach) między dwoma kliknięciami PPM w siekierkę. */
    public int axeClickGapTicks = 1;
    /** Przed skanowaniem ustaw w rynku sortowanie na "Najnowsze" (przycisk "Sortowanie" - repeater, przełączany PPM). */
    public boolean ensureNewest = true;
    /** Opcja sortowania, która ma być aktywna (fragment tekstu w opisie przycisku, bez wielkości liter i polskich znaków). */
    public String sortTarget = "najnowsze";
    /** Znacznik nieaktywnych opcji w opisie przycisku sortowania (aktywna ma inny, np. strzałkę). */
    public String sortUnselectedMarker = "»";
    /** Fragmenty nazwy przycisku sortowania. */
    public List<String> sortNameKeywords = new ArrayList<>(Arrays.asList("sortowanie"));
    /** Numer slotu przycisku sortowania; -1 = wykryj automatycznie (po nazwie i opisie). */
    public int sortSlot = -1;
    /** Ile razy kliknąć PPM w przycisk sortowania po otwarciu rynku (Brak -> Najwyższej -> Najniższej -> Najnowsze = 3). */
    public int sortClicks = 3;
    /** Przerwa (w tickach) między kolejnymi kliknięciami sortowania. */
    public int sortClickGapTicks = 2;
    /** Ile ticków czekać po ostatnim kliknięciu sortowania, aż serwer odświeży rynek. */
    public int sortWaitTicks = 6;
    /** Minimalny odstęp (w tickach) między dwiema seriami kliknięć sortowania - zabezpieczenie przed pętlą. */
    public int sortMinGapTicks = 200;
    /** Numer slotu siekierki (0 = lewy górny róg okna); -1 = wykryj automatycznie. */
    public int refreshSlot = -1;
    /** Dodatkowe fragmenty nazwy przycisku odświeżania - gdy siekierka nie jest wykrywana po typie przedmiotu. */
    public List<String> refreshNameKeywords = new ArrayList<>();
    /** Jeśli tytuł okna nie pasuje, a w oknie są oferty z ceną - i tak traktuj je jako rynek. */
    public boolean anyChestAsMarket = true;
    /** Nieużywane (zostawione dla zgodności) - ceny ustawia się przy każdym przedmiocie osobno. */
    public double defaultMaxPrice = 0;
    public List<Entry> entries = new ArrayList<>();

    // --- Ustawienia zaawansowane (edytowalne w pliku JSON) ---
    /** Komenda otwierająca rynek (bez ukośnika). */
    public String marketCommand = "rynek";
    /** Czy mod sam otwiera rynek co refreshIntervalTicks. */
    public boolean autoOpenMarket = true;
    /** Co ile ticków (20 = 1 s) mod otwiera rynek. */
    public int refreshIntervalTicks = 200;
    /** Fragment tytułu okna rynku (bez wielkości liter i polskich znaków). */
    public String marketTitleContains = "rynek";
    /** Ile ticków odczekać po otwarciu okna / zmianie strony, zanim zacznie skanowanie. */
    public int settleTicks = 10;
    /** Przerwa po kliknięciu oferty. */
    public int afterPurchaseCooldownTicks = 40;
    /** Ta sama oferta (nazwa + cena) nie będzie klikana ponownie przez tyle ticków. */
    public int retryBlockTicks = 600;
    /** Maks. liczba prób zakupu w jednej sesji gry; 0 = bez limitu. Po osiągnięciu mod się wyłącza. */
    public int maxPurchasesPerSession = 0;
    /** Ile stron rynku przejrzeć (tylko gdy mod sam otworzył rynek i znajdzie przycisk następnej strony). */
    public int maxPages = 3;
    /** "PICKUP" (zwykłe kliknięcie) albo "QUICK_MOVE" (shift+klik). */
    public String clickMode = "PICKUP";
    /** Regex ceny w opisie przedmiotu: grupa 1 = liczba, grupa 2 = opcjonalny sufiks k/m. */
    public String priceRegex = DEFAULT_PRICE_REGEX;
    /** Czy dopasowywać też do linii opisu (lore), a nie tylko do nazwy. */
    public boolean matchLore = false;
    public List<String> nextPageKeywords = new ArrayList<>(Arrays.asList("nastepna", "nastepn", "dalej", "next", "→", ">>"));
    public List<String> confirmTitleKeywords = new ArrayList<>(Arrays.asList("potwierdz", "zakup", "kupno", "confirm"));
    public List<String> confirmButtonKeywords = new ArrayList<>(Arrays.asList("potwierdz", "kup", "tak", "zaakceptuj", "confirm"));

    /** Fragmenty id przedmiotu przycisku potwierdzenia (gdy nazwa nie pasuje) - np. zielone szkło/wełna/emerald_block. */
    public List<String> confirmItemKeywords = new ArrayList<>(Arrays.asList("lime", "green", "emerald_block"));
    /** Automatycznie klikaj przycisk potwierdzenia zakupu po kliknięciu oferty. */
    public boolean autoConfirm = true;
    /** W oknie potwierdzenia sprawdź cenę ("Cena: ...") i anuluj zakup, jeśli jest wyższa niż limit oferty. */
    public boolean guardConfirmPrice = true;
    /** Szybkie potwierdzanie: okno "Zakup produkt" jest klikane w tym samym ticku, w którym się otworzy (bez czekania). */
    public boolean instantConfirm = true;
    /** Potwierdzaj od razu także zakupy wybrane ręcznie (gdy mod jest włączony). Wyłącz, jeśli chcesz sam klikać "Zakup produkt". */
    public boolean confirmManualPurchases = true;
    /** Fragmenty nazwy przycisku potwierdzenia dla szybkiego potwierdzania (bez wielkości liter i polskich znaków). */
    public List<String> instantConfirmKeywords = new ArrayList<>(Arrays.asList("zakup", "potwierdz", "zatwierdz", "akceptuj", "zaakceptuj", "confirm", "kupno"));

    private transient Pattern cachedPattern;
    private transient String cachedPatternSource;

    public static AutoRynekConfig get() {
        return instance;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("anarchia_autorynek.json");
    }

    public static synchronized void load() {
        Path p = path();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                AutoRynekConfig loaded = GSON.fromJson(r, AutoRynekConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (Exception e) {
                AutoRynekClient.LOGGER.error("Nie udało się wczytać konfiguracji, używam ustawień domyślnych", e);
                // uszkodzony plik zachowujemy jako kopię, żeby go nie nadpisać bez śladu
                try {
                    Files.move(p, p.resolveSibling("anarchia_autorynek.json.uszkodzony"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // nic więcej nie zrobimy
                }
                instance = new AutoRynekConfig();
            }
        }
        instance.sanitize();
        save();
    }

    public static synchronized void save() {
        Path p = path();
        Path tmp = p.resolveSibling("anarchia_autorynek.json.tmp");
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AutoRynekClient.LOGGER.error("Nie udało się zapisać konfiguracji", e);
        }
    }

    private void sanitize() {
        if (entries == null) entries = new ArrayList<>();
        entries.removeIf(e -> e == null || e.name == null || e.name.isBlank());
        for (Entry e : entries) {
            if (e.category == null) e.category = "EVENT";
            if (e.maxPrice < 0 || Double.isNaN(e.maxPrice)) e.maxPrice = 0;
        }
        defaultMaxPrice = 0; // brak ceny domyślnej - liczą się tylko ceny przedmiotów
        if (marketCommand == null || marketCommand.isBlank()) marketCommand = "rynek";
        if (marketCommand.startsWith("/")) marketCommand = marketCommand.substring(1);
        if (marketTitleContains == null) marketTitleContains = "rynek";
        if (configVersion < CONFIG_VERSION) {
            if (OLD_PRICE_REGEX.equals(priceRegex)) priceRegex = DEFAULT_PRICE_REGEX;
            if (refreshIntervalTicks >= 200) refreshIntervalTicks = 30;
            if (settleTicks >= 10) settleTicks = 4;
            if (afterPurchaseCooldownTicks >= 40) afterPurchaseCooldownTicks = 10;
            sniper = true;
            anyChestAsMarket = true;
            if (configVersion < 3) {
                speed = 0;
                firstPageOnly = true;
            }
            configVersion = CONFIG_VERSION;
        }
        refreshIntervalTicks = Math.max(2, refreshIntervalTicks);
        speed = Math.max(0, Math.min(SPEED_REFRESH.length - 1, speed));
        settleTicks = Math.max(1, settleTicks);
        afterPurchaseCooldownTicks = Math.max(2, afterPurchaseCooldownTicks);
        retryBlockTicks = Math.max(0, retryBlockTicks);
        maxPurchasesPerSession = Math.max(0, maxPurchasesPerSession);
        maxPages = Math.max(1, Math.min(20, maxPages));
        if (confirmItemKeywords == null) confirmItemKeywords = new ArrayList<>(Arrays.asList("lime", "green", "emerald_block"));
        axeClickGapTicks = Math.max(1, Math.min(20, axeClickGapTicks));
        if (sortTarget == null || sortTarget.isBlank()) sortTarget = "najnowsze";
        if (sortUnselectedMarker == null || sortUnselectedMarker.isBlank()) sortUnselectedMarker = "»";
        if (sortNameKeywords == null || sortNameKeywords.isEmpty()) sortNameKeywords = new ArrayList<>(Arrays.asList("sortowanie"));
        sortSlot = Math.max(-1, sortSlot);
        sortClicks = Math.max(1, Math.min(12, sortClicks));
        sortClickGapTicks = Math.max(1, Math.min(40, sortClickGapTicks));
        sortMinGapTicks = Math.max(20, sortMinGapTicks);
        sortWaitTicks = Math.max(2, Math.min(40, sortWaitTicks));
        refreshSlot = Math.max(-1, refreshSlot);
        if (refreshNameKeywords == null) refreshNameKeywords = new ArrayList<>();
        if (clickMode == null) clickMode = "PICKUP";
        if (priceRegex == null || priceRegex.isBlank()) priceRegex = DEFAULT_PRICE_REGEX;
        if (nextPageKeywords == null) nextPageKeywords = new ArrayList<>();
        if (confirmTitleKeywords == null) confirmTitleKeywords = new ArrayList<>();
        if (confirmButtonKeywords == null) confirmButtonKeywords = new ArrayList<>();
        if (instantConfirmKeywords == null || instantConfirmKeywords.isEmpty()) {
            instantConfirmKeywords = new ArrayList<>(Arrays.asList("zakup", "potwierdz", "zatwierdz", "akceptuj", "zaakceptuj", "confirm", "kupno"));
        }
    }

    public int refreshTicks() { return sniper ? SPEED_REFRESH[speed] : refreshIntervalTicks; }
    public int settle() { return sniper ? 1 : settleTicks; }
    public int cooldown() { return sniper ? 4 : afterPurchaseCooldownTicks; }
    public int recheckTicks() { return sniper ? 1 : 20; }
    public int emptyWaitTicks() { return sniper ? 1 : 5; }

    /** Skompilowany regex ceny; przy błędnym wyrażeniu wraca do domyślnego. */
    public Pattern pricePattern() {
        if (cachedPattern == null || !priceRegex.equals(cachedPatternSource)) {
            try {
                cachedPattern = Pattern.compile(priceRegex);
            } catch (PatternSyntaxException e) {
                AutoRynekClient.LOGGER.warn("Błędny regex ceny w konfiguracji, używam domyślnego: {}", e.getMessage());
                cachedPattern = Pattern.compile(DEFAULT_PRICE_REGEX);
            }
            cachedPatternSource = priceRegex;
        }
        return cachedPattern;
    }
}
