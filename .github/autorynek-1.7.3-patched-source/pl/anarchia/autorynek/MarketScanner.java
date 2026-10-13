package pl.anarchia.autorynek;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import net.minecraft.class_124;
import net.minecraft.class_1703;
import net.minecraft.class_1707;
import net.minecraft.class_1713;
import net.minecraft.class_1735;
import net.minecraft.class_1799;
import net.minecraft.class_2561;
import net.minecraft.class_310;
import net.minecraft.class_437;
import net.minecraft.class_465;
import net.minecraft.class_746;
import net.minecraft.class_7923;
import net.minecraft.class_9290;
import net.minecraft.class_9334;

/**
 * Logika sprawdzania rynku. Działa wyłącznie na standardowym oknie skrzyni (GenericContainerScreen):
 * czyta nazwy i opisy przedmiotów, wyciąga cenę z opisu i klika ofertę, jeśli pasuje do listy i mieści się w limicie.
 *
 * Mod NIE zna układu rynku konkretnego serwera - to, czy zakup zadziała, zależy od tego, czy rynek serwera
 * jest zwykłym oknem skrzyni z ceną w opisie przedmiotu. Do dopasowania służy plik konfiguracji i /autorynek diagnoza.
 */
public final class MarketScanner {
    private static final class Candidate {
        class_1735 slot;
        String displayName;
        double price;
        double limit;
        String key;
        String priceLine;
    }

    private long tick = 0;
    private int sinceRefresh = 0;
    private int lastSyncId = -1;
    private long nextActionAt = 0;
    private int pagesScanned = 0;
    private int emptyChecks = 0;
    private boolean finished = false;
    private long modOpenedAt = Long.MIN_VALUE / 2;
    private long lastBuyAttemptTick = Long.MIN_VALUE / 2;
    private int purchases = 0;
    private boolean diagnosticRequested = false;
    /** 0 = następne kliknięcie w siekierkę będzie pierwsze, 1 = drugie z pary. */
    private int axeStage = 0;
    /** Ile kliknięć sortowania zostało do wykonania w bieżącej serii. */
    private int sortBurstLeft = 0;
    /** Czy po otwarciu rynku trzeba jeszcze sprawdzić / ustawić sortowanie. */
    private boolean sortArmed = true;
    private long lastBurstTick = Long.MIN_VALUE / 2;
    /** Limit i nazwa oferty, na którą kliknięto - do sprawdzenia ceny w oknie potwierdzenia. */
    private double pendingLimit = 0;
    private String pendingName = "";
    private int buySyncId = -1;
    private boolean confirmHintShown = false;
    /** Okno potwierdzenia, które już kliknięto (żeby nie klikać go wielokrotnie, zanim serwer je zamknie). */
    private int confirmedSyncId = -1;
    private long confirmedAt = Long.MIN_VALUE / 2;
    private static final Pattern CANCEL_WORDS = Pattern.compile("(?<![a-z])(anuluj|cancel|odrzuc|wroc|zamknij|cofnij|nie)(?![a-z])");
    private final Map<String, Long> attempts = new HashMap<>();

    /** Ostatni stan skanowania - pokazywany w menu i w /autorynek status. */
    public static volatile String lastInfo = "Jeszcze nie skanowano rynku.";
    public static volatile int totalBuyClicks = 0;
    public static volatile int scans = 0;
    /** Ostatnie zdarzenie zakupowe (do podświetlenia w menu). */
    public static volatile String lastBuyInfo = "";
    public static volatile long lastBuyMs = 0;

    private static void noteBuy(String info) {
        lastBuyInfo = info;
        lastBuyMs = System.currentTimeMillis();
    }

    public void requestDiagnostic() {
        diagnosticRequested = true;
    }

    public int purchaseAttempts() {
        return purchases;
    }

    private void resetSession() {
        lastSyncId = -1;
        finished = false;
        sinceRefresh = 0;
        attempts.clear();
    }

    public void tick(class_310 client) {
        tick++;
        MarketOverlay.ensureRegistered();
        class_746 player = client.field_1724;
        if (player == null || client.field_1687 == null || client.field_1761 == null) {
            resetSession();
            return;
        }
        AutoRynekConfig cfg = AutoRynekConfig.get();
        class_437 screen = client.field_1755;

        if (screen instanceof class_465<?> gcs) {
            class_1703 handler = gcs.method_17577();
            if (handler.field_7763 != lastSyncId) {
                lastSyncId = handler.field_7763;
                confirmedSyncId = -1;
                pagesScanned = 0;
                emptyChecks = 0;
                finished = false;
                // Każde nowe okno może przywrócić domyślne sortowanie serwera.
                // Sprawdź „Najnowsze” ponownie po powrocie z potwierdzenia zakupu.
                sortArmed = true;
                sortBurstLeft = 0;
                nextActionAt = tick + cfg.settle();
            }
            // Okno potwierdzenia ("Zakup produkt") klikamy natychmiast - bez czekania na settle/cooldown.
            if (cfg.enabled && cfg.autoConfirm && cfg.instantConfirm
                    && instantConfirm(client, player, gcs, handler, cfg)) {
                return;
            }
            if (tick < nextActionAt) {
                return;
            }
            if (diagnosticRequested) {
                diagnosticRequested = false;
                dumpDiagnostics(client, gcs, cfg);
            }
            if (cfg.enabled) {
                handleContainer(client, player, gcs, handler, cfg);
            }
            return;
        }

        lastSyncId = -1;
        finished = false;
        if (screen != null) {
            return; // czat, menu moda itp. - nie przeszkadzamy
        }
        if (!cfg.enabled || !cfg.autoOpenMarket || cfg.axeRefresh) {
            // w trybie siekierki mod nie otwiera ani nie zamyka rynku - rynek otwierasz sam (/rynek)
            sinceRefresh = 0;
            return;
        }
        sinceRefresh++;
        if (sinceRefresh >= cfg.refreshTicks()) {
            sinceRefresh = 0;
            if (player.field_3944 != null) {
                player.field_3944.method_45730(cfg.marketCommand);
                modOpenedAt = tick;
            }
        }
    }

    private void handleContainer(class_310 client, class_746 player, class_437 gcs,
                                 class_1703 handler, AutoRynekConfig cfg) {
        String title = norm(gcs.method_25440().getString());
        int containerSlots = containerSlotCount(handler);
        boolean openedByMod = tick - modOpenedAt <= 200;

        boolean titleIsMarket = title.contains(norm(cfg.marketTitleContains));
        boolean recentBuy = tick - lastBuyAttemptTick <= 200;
        Pattern pp = cfg.pricePattern();
        int pricedCount = 0;
        int matchedCount = 0;
        int filled = 0;
        for (int i = 0; i < containerSlots; i++) {
            class_1799 st = handler.field_7761.get(i).method_7677();
            if (st.method_7960()) {
                continue;
            }
            filled++;
            List<String> lore = loreLines(st);
            if (PriceParser.findPrice(lore, pp).isPresent()) {
                pricedCount++;
                if (matchEntry(cfg, st, norm(st.method_7964().getString()), lore) != null) {
                    matchedCount++;
                }
            }
        }
        lastInfo = "Okno: \"" + gcs.method_25440().getString() + "\" | przedmiotów: " + filled
                + " | z ceną: " + pricedCount + " | pasuje do listy: " + matchedCount
                + (titleIsMarket ? " | tytuł OK" : " | tytuł nie pasuje");

        // 1) Okno potwierdzenia - klikamy tylko tuż po próbie zakupu zainicjowanej przez mod.
        boolean looksLikeMarket = pricedCount >= 3; // zwykłe okno rynku ma wiele ofert z ceną
        boolean otherWindow = handler.field_7763 != buySyncId;
        boolean confirmByTitle = containsAny(title, cfg.confirmTitleKeywords);
        class_1735 confirm = null;
        if (cfg.autoConfirm && (confirmByTitle || (recentBuy && !looksLikeMarket))) {
            // w tym samym oknie co oferta szukamy ostrożniej (tylko wyraźne "potwierdź/akceptuj" albo zielony przycisk)
            confirm = findConfirmButton(handler, containerSlots, cfg, !otherWindow && !confirmByTitle);
        }
        boolean confirmContext = cfg.autoConfirm
                && (confirmByTitle || (recentBuy && !looksLikeMarket && (otherWindow || confirm != null)));
        if (confirmContext) {
            if (recentBuy) {
                if (confirm != null) {
                    if (cfg.guardConfirmPrice) {
                        PriceParser.Found shown = confirmWindowPrice(handler, containerSlots, cfg);
                        if (shown != null && shown.value > pendingLimit + 1e-9) {
                            String msg = "Anulowano zakup: " + pendingName + " - w oknie potwierdzenia cena "
                                    + PriceParser.format(shown.value) + " > limit " + PriceParser.format(pendingLimit) + ".";
                            chat(player, msg, class_124.field_1061);
                            noteBuy(msg);
                            lastBuyAttemptTick = Long.MIN_VALUE / 2;
                            nextActionAt = tick + cfg.cooldown();
                            player.method_7346();
                            return;
                        }
                    }
                    click(client, handler, confirm, cfg);
                    chat(player, "Potwierdzono zakup: " + pendingName + " (limit " + PriceParser.format(pendingLimit) + ").",
                            class_124.field_1060);
                    noteBuy("Potwierdzono: " + pendingName);
                    lastBuyAttemptTick = Long.MIN_VALUE / 2; // jedno potwierdzenie na jedną próbę zakupu
                    nextActionAt = tick + cfg.cooldown();
                } else if (!confirmHintShown && tick - lastBuyAttemptTick >= 10) {
                    // okno po kliknięciu oferty jest, ale nie wiemy, który przycisk potwierdza - zrzut do logu
                    confirmHintShown = true;
                    dumpDiagnostics(client, gcs, cfg);
                    chat(player, "Po kliknięciu oferty otworzyło się okno \"" + gcs.method_25440().getString()
                            + "\", ale nie znalazłem w nim przycisku potwierdzenia. Zrzut okna jest w logs/latest.log"
                            + " (linie [diagnoza]) - wyślij mi go albo screenshot tego okna.", class_124.field_1061);
                }
            }
            return;
        }

        // 2) Okno rynku
        if (finished) {
            return;
        }
        if (!titleIsMarket && !(cfg.anyChestAsMarket && pricedCount > 0)) {
            return;
        }

        boolean anyItem = false;
        for (int i = 0; i < containerSlots; i++) {
            if (!handler.field_7761.get(i).method_7677().method_7960()) {
                anyItem = true;
                break;
            }
        }
        if (!anyItem) {
            // zawartość mogła jeszcze nie dotrzeć z serwera
            emptyChecks++;
            if (cfg.axeRefresh) {
                nextActionAt = tick + cfg.emptyWaitTicks(); // po odświeżeniu czekamy aż oferty się załadują
                return;
            }
            if (emptyChecks > 20) {
                finishScan(player, openedByMod);
            } else {
                nextActionAt = tick + cfg.emptyWaitTicks();
            }
            return;
        }

        attempts.entrySet().removeIf(e -> tick - e.getValue() > cfg.retryBlockTicks);

        if (cfg.ensureNewest && ensureSort(client, player, handler, containerSlots, cfg)) {
            return; // kliknięto sortowanie - czekamy aż serwer odświeży rynek
        }

        Candidate best = findBestOffer(handler, containerSlots, cfg);
        if (best != null) {
            if (cfg.maxPurchasesPerSession > 0 && purchases >= cfg.maxPurchasesPerSession) {
                cfg.enabled = false;
                AutoRynekConfig.save();
                chat(player, "Osiągnięto limit prób zakupu w tej sesji (" + cfg.maxPurchasesPerSession
                        + "). AutoRynek został wyłączony.", class_124.field_1061);
                if (openedByMod) {
                    player.method_7346();
                }
                return;
            }
            axeStage = 0;
            confirmHintShown = false;
            pendingLimit = best.limit;
            pendingName = best.displayName;
            buySyncId = handler.field_7763;
            attempts.put(best.key, tick);
            lastBuyAttemptTick = tick;
            purchases++;
            totalBuyClicks++;
            click(client, handler, best.slot, cfg);
            chat(player, "Próba zakupu: " + best.displayName + " za " + PriceParser.format(best.price)
                    + " (limit " + PriceParser.format(best.limit) + ") | z opisu: \"" + best.priceLine + "\"", class_124.field_1060);
            noteBuy("Próba zakupu: " + best.displayName + " za " + PriceParser.format(best.price));
            nextActionAt = tick + cfg.cooldown();
            return;
        }

        // Nic do kupienia na tej stronie
        if (cfg.axeRefresh) {
            refreshWithAxe(client, handler, containerSlots, cfg);
            return;
        }
        if (openedByMod) {
            if (!cfg.firstPageOnly && pagesScanned + 1 < cfg.maxPages) {
                class_1735 next = findByName(handler, containerSlots, cfg.nextPageKeywords);
                if (next != null) {
                    pagesScanned++;
                    click(client, handler, next, cfg);
                    nextActionAt = tick + cfg.settle();
                    return;
                }
            }
            finishScan(player, true);
        } else {
            nextActionAt = tick + cfg.recheckTicks(); // ręcznie otwarty rynek - sprawdzamy ponownie za chwilę
        }
    }

    /**
     * Szybkie potwierdzenie zakupu: małe okno (do 4 przedmiotów) z przyciskiem typu "Zakup produkt" jest klikane
     * od razu w pierwszym ticku, w którym ma zawartość. Dotyczy zakupów zainicjowanych przez mod (z kontrolą ceny
     * względem limitu) oraz - gdy confirmManualPurchases - wybranych ręcznie. Zwraca true, gdy okno zostało obsłużone.
     */
    private boolean instantConfirm(class_310 client, class_746 player, class_437 gcs, class_1703 handler, AutoRynekConfig cfg) {
        if (handler.field_7763 == confirmedSyncId) {
            return tick - confirmedAt <= 40; // czekamy aż serwer zamknie okno; po 2 s oddajemy sterowanie
        }
        boolean mine = tick - lastBuyAttemptTick <= 200 && handler.field_7763 != buySyncId;
        if (!mine && !cfg.confirmManualPurchases) {
            return false;
        }
        int containerSlots = containerSlotCount(handler);
        int filled = 0;
        for (int i = 0; i < containerSlots; i++) {
            if (!handler.field_7761.get(i).method_7677().method_7960()) {
                filled++;
            }
        }
        if (filled == 0) {
            return false; // puste (jeszcze się ładuje)
        }
        class_1735 button = null;
        // Anarchia.GG używa jasnozielonego barwnika z nazwą „Zakup produkt”.
        // Szukaj tego jednoznacznego przycisku przed limitem liczby wypełnionych slotów:
        // okno może mieć też dekoracyjne szkła i inne wypełnione sloty.
        for (int i = 0; i < containerSlots; i++) {
            class_1735 slot = handler.field_7761.get(i);
            class_1799 st = slot.method_7677();
            if (st.method_7960()) {
                continue;
            }
            String name = norm(st.method_7964().getString());
            String id = class_7923.field_41178.method_10221(st.method_7909()).toString().toLowerCase(Locale.ROOT);
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (CANCEL_WORDS.matcher(name).find()) {
                continue;
            }
            if (name.contains("zakup produkt") || path.equals("lime_dye")) {
                button = slot;
                break;
            }
        }
        // Awaryjne dopasowanie po nazwie zostawiamy tylko dla małych okien,
        // żeby nie pomylić zwykłej oferty na rynku z przyciskiem potwierdzenia.
        if (button == null && filled <= 4) {
            for (int i = 0; i < containerSlots; i++) {
                class_1735 slot = handler.field_7761.get(i);
                class_1799 st = slot.method_7677();
                if (st.method_7960()) {
                    continue;
                }
                String name = norm(st.method_7964().getString());
                if (CANCEL_WORDS.matcher(name).find()) {
                    continue;
                }
                if (containsAny(name, cfg.instantConfirmKeywords)) {
                    button = slot;
                    break;
                }
            }
        }
        if (button == null) {
            return false;
        }
        if (mine && cfg.guardConfirmPrice) {
            PriceParser.Found shown = confirmWindowPrice(handler, containerSlots, cfg);
            if (shown != null && shown.value > pendingLimit + 1e-9) {
                String msg = "Anulowano zakup: " + pendingName + " - w oknie potwierdzenia cena "
                        + PriceParser.format(shown.value) + " > limit " + PriceParser.format(pendingLimit) + ".";
                chat(player, msg, class_124.field_1061);
                noteBuy(msg);
                lastBuyAttemptTick = Long.MIN_VALUE / 2;
                nextActionAt = tick + cfg.cooldown();
                player.method_7346();
                return true;
            }
        }
        click(client, handler, button, cfg);
        confirmedSyncId = handler.field_7763;
        confirmedAt = tick;
        String what = mine ? pendingName : "ręcznie wybrany przedmiot";
        chat(player, "Potwierdzono zakup: " + what + ".", class_124.field_1060);
        noteBuy("Potwierdzono: " + what);
        lastBuyAttemptTick = Long.MIN_VALUE / 2;
        nextActionAt = tick + cfg.cooldown();
        return true;
    }

    /**
     * Po otwarciu rynku klika PPM w przycisk "Sortowanie" tyle razy, ile ustawiono w sortClicks (domyślnie 3:
     * Brak -> Najwyższej ceny -> Najniższej ceny -> Najnowsze). Jeśli opis przycisku wyraźnie pokazuje, że
     * "Najnowsze" jest już aktywne, nie klika. Zwraca true, gdy kliknął w tym ticku.
     */
    private boolean ensureSort(class_310 client, class_746 player, class_1703 handler, int containerSlots, AutoRynekConfig cfg) {
        if (sortBurstLeft <= 0 && !sortArmed) {
            return false;
        }
        class_1735 slot = findSortSlot(handler, containerSlots, cfg);
        if (slot == null) {
            return false; // przycisk jeszcze się nie załadował albo go nie ma
        }
        if (sortBurstLeft <= 0) {
            sortArmed = false;
            if (sortIsTarget(loreLines(slot.method_7677()), cfg)) {
                return false;
            }
            sortBurstLeft = cfg.sortClicks;
        }
        // Sprawdzaj stan po każdym kliknięciu; jeśli „Najnowsze” już aktywne,
        // nie kontynuuj cyklu i nie przestawiaj go przypadkowo na inną opcję.
        if (sortIsTarget(loreLines(slot.method_7677()), cfg)) {
            sortBurstLeft = 0;
            sortArmed = false;
            return false;
        }
        axeStage = 0;
        rightClick(client, handler, slot);
        sortBurstLeft--;
        lastBurstTick = tick;
        nextActionAt = tick + (sortBurstLeft > 0 ? cfg.sortClickGapTicks : cfg.sortWaitTicks);
        return true;
    }

    /** Aktywna opcja ma w opisie linię z tekstem sortTarget bez znacznika nieaktywnych opcji (»). */
    private static boolean sortIsTarget(List<String> lore, AutoRynekConfig cfg) {
        String target = norm(cfg.sortTarget);
        String marker = norm(cfg.sortUnselectedMarker);
        for (String l : lore) {
            String n = norm(l);
            if (!n.contains(target)) {
                continue;
            }
            return !n.startsWith(marker);
        }
        return false;
    }

    /**
     * Przycisk sortowania: ustawiony slot albo przedmiot o nazwie "Sortowanie" bez ceny.
     * Najpierw szukamy takiego, którego opis zawiera sortTarget, potem samego po nazwie.
     */
    private static class_1735 findSortSlot(class_1703 handler, int containerSlots, AutoRynekConfig cfg) {
        if (cfg.sortSlot >= 0 && cfg.sortSlot < containerSlots) {
            return handler.field_7761.get(cfg.sortSlot);
        }
        Pattern pp = cfg.pricePattern();
        String target = norm(cfg.sortTarget);
        class_1735 byNameOnly = null;
        for (int i = 0; i < containerSlots; i++) {
            class_1735 slot = handler.field_7761.get(i);
            class_1799 st = slot.method_7677();
            if (st.method_7960()) {
                continue;
            }
            if (!containsAny(norm(st.method_7964().getString()), cfg.sortNameKeywords)) {
                continue;
            }
            List<String> lore = loreLines(st);
            if (PriceParser.findPrice(lore, pp).isPresent()) {
                continue;
            }
            for (String l : lore) {
                if (norm(l).contains(target)) {
                    return slot;
                }
            }
            if (byNameOnly == null) {
                byNameOnly = slot;
            }
        }
        return byNameOnly;
    }

    /** Odświeżenie rynku: dwa kliknięcia PPM w siekierkę (z małą przerwą), bez zamykania okna. */
    private void refreshWithAxe(class_310 client, class_1703 handler, int containerSlots, AutoRynekConfig cfg) {
        class_1735 axe = findRefreshSlot(handler, containerSlots, cfg);
        if (axe == null) {
            axeStage = 0;
            lastInfo = lastInfo + " | brak siekierki odświeżania (ustaw refreshSlot w configu)";
            nextActionAt = tick + 10;
            return;
        }
        rightClick(client, handler, axe);
        if (axeStage == 0) {
            axeStage = 1;
            nextActionAt = tick + cfg.axeClickGapTicks;
        } else {
            axeStage = 0;
            scans++;
            nextActionAt = tick + cfg.refreshTicks();
        }
    }

    /**
     * Szuka przycisku odświeżania: ustawiony ręcznie slot (refreshSlot) albo przedmiot typu *_axe bez ceny w opisie
     * (oferty zawsze mają cenę, więc siekierka-oferta nie zostanie pomylona z przyciskiem).
     */
    private static class_1735 findRefreshSlot(class_1703 handler, int containerSlots, AutoRynekConfig cfg) {
        if (cfg.refreshSlot >= 0 && cfg.refreshSlot < containerSlots) {
            return handler.field_7761.get(cfg.refreshSlot);
        }
        Pattern pp = cfg.pricePattern();
        class_1735 found = null;
        boolean foundInLeftColumn = false;
        for (int i = 0; i < containerSlots; i++) {
            class_1735 slot = handler.field_7761.get(i);
            class_1799 st = slot.method_7677();
            if (st.method_7960()) {
                continue;
            }
            String id = class_7923.field_41178.method_10221(st.method_7909()).toString().toLowerCase(Locale.ROOT);
            boolean byType = id.endsWith("_axe");
            boolean byName = !cfg.refreshNameKeywords.isEmpty()
                    && containsAny(norm(st.method_7964().getString()), cfg.refreshNameKeywords);
            if (!byType && !byName) {
                continue;
            }
            if (PriceParser.findPrice(loreLines(st), pp).isPresent()) {
                continue; // to oferta, nie przycisk
            }
            // przyciski menu rynku są w lewej kolumnie (sloty 0, 9, 18...) - ona ma pierwszeństwo
            if (i % 9 == 0) {
                found = slot;
                foundInLeftColumn = true;
            } else if (!foundInLeftColumn) {
                found = slot;
            }
        }
        return found;
    }

    /**
     * Liczba slotów samego okna (bez ekwipunku gracza): dla skrzyni - wiersze * 9, dla innych okien
     * (dozownik, lejek itp.) - wszystkie sloty poza 36 slotami ekwipunku.
     */
    private static int containerSlotCount(class_1703 handler) {
        if (handler instanceof class_1707 chest) {
            return chest.method_17388() * 9;
        }
        return Math.max(0, handler.field_7761.size() - 36);
    }

    private static void rightClick(class_310 client, class_1703 handler, class_1735 slot) {
        client.field_1761.method_2906(handler.field_7763, slot.field_7874, 1, class_1713.field_7790, client.field_1724);
    }

    private void finishScan(class_746 player, boolean closeScreen) {
        finished = true;
        scans++;
        sinceRefresh = 0;
        if (closeScreen) {
            modOpenedAt = Long.MIN_VALUE / 2;
            player.method_7346();
        }
    }

    private Candidate findBestOffer(class_1703 handler, int containerSlots, AutoRynekConfig cfg) {
        if (cfg.entries.isEmpty()) {
            return null;
        }
        Pattern pricePattern = cfg.pricePattern();
        Candidate best = null;
        for (int i = 0; i < containerSlots; i++) {
            class_1735 slot = handler.field_7761.get(i);
            class_1799 stack = slot.method_7677();
            if (stack.method_7960()) {
                continue;
            }
            String rawName = stack.method_7964().getString();
            String name = norm(rawName);
            List<String> lore = loreLines(stack);
            AutoRynekConfig.Entry entry = matchEntry(cfg, stack, name, lore);
            if (entry == null) {
                continue;
            }
            PriceParser.Found found = PriceParser.find(lore, pricePattern);
            if (found == null) {
                continue; // brak rozpoznanej ceny = nie kupujemy
            }
            double limit = entry.maxPrice;
            if (limit <= 0) {
                continue; // przedmiot bez ustawionej ceny nie jest kupowany
            }
            if (found.value > limit) {
                continue; // droższe niż wybrana cena
            }
            String key = name + "|" + found.value;
            if (attempts.containsKey(key)) {
                continue;
            }
            if (best == null || found.value < best.price) {
                best = new Candidate();
                best.slot = slot;
                best.displayName = rawName.replaceAll("§.", "");
                best.price = found.value;
                best.limit = limit;
                best.key = key;
                best.priceLine = found.line;
            }
        }
        return best;
    }

    private static AutoRynekConfig.Entry matchEntry(AutoRynekConfig cfg, class_1799 stack, String name, List<String> lore) {
        for (AutoRynekConfig.Entry e : cfg.entries) {
            String key = norm(e.name);
            if (key.isEmpty()) {
                continue;
            }
            if (key.startsWith("id:")) {
                String wanted = key.substring(3).trim();
                String id = class_7923.field_41178.method_10221(stack.method_7909()).toString().toLowerCase(Locale.ROOT);
                if (!wanted.isEmpty() && id.contains(wanted)) {
                    return e;
                }
                continue;
            }
            if (name.contains(key)) {
                return e;
            }
            if (cfg.matchLore) {
                for (String l : lore) {
                    if (norm(l).contains(key)) {
                        return e;
                    }
                }
            }
        }
        return null;
    }

    private static List<String> loreLines(class_1799 stack) {
        List<String> out = new ArrayList<>();
        class_9290 lore = stack.method_58694(class_9334.field_49632);
        if (lore != null) {
            for (class_2561 t : lore.comp_2400()) {
                out.add(t.getString());
            }
        }
        return out;
    }

    private static class_1735 findByName(class_1703 handler, int containerSlots, List<String> keywords) {
        for (int i = 0; i < containerSlots; i++) {
            class_1735 slot = handler.field_7761.get(i);
            class_1799 stack = slot.method_7677();
            if (stack.method_7960()) {
                continue;
            }
            if (containsAny(norm(stack.method_7964().getString()), keywords)) {
                return slot;
            }

        }
        return null;
    }

    /**
     * Przycisk potwierdzenia: po nazwie, a jeśli się nie da - po typie przedmiotu (zielone szkło/wełna/beton, blok
     * szmaragdu) bez ceny w opisie. Tryb strict pomija krótkie, mało pewne słowa kluczowe ("kup", "tak").
     */
    private static class_1735 findConfirmButton(class_1703 handler, int containerSlots, AutoRynekConfig cfg, boolean strict) {
        // 1) wyraźne słowa: potwierdź / zatwierdź / akceptuj / confirm (zawsze, niezależnie od zapisanego configu)
        List<String> clear = new ArrayList<>(List.of("potwierdz", "zatwierdz", "akceptuj", "zaakceptuj", "confirm"));
        for (String k : cfg.confirmButtonKeywords) {
            if (norm(k).length() > 3 && !clear.contains(norm(k))) {
                clear.add(k);
            }
        }
        class_1735 byName = findByName(handler, containerSlots, clear);
        if (byName != null) {
            return byName;
        }
        // 2) po typie przedmiotu: zielony barwnik / zielone szkło, wełna, beton, blok szmaragdu
        Pattern pp = cfg.pricePattern();
        List<String> idKeywords = new ArrayList<>(cfg.confirmItemKeywords);
        for (String k : List.of("lime", "green", "emerald_block")) {
            if (!idKeywords.contains(k)) {
                idKeywords.add(k);
            }
        }
        for (int i = 0; i < containerSlots; i++) {
            class_1735 slot = handler.field_7761.get(i);
            class_1799 st = slot.method_7677();
            if (st.method_7960()) {
                continue;
            }
            String id = class_7923.field_41178.method_10221(st.method_7909()).toString().toLowerCase(Locale.ROOT);
            if (!idMatches(id, idKeywords)) {
                continue;
            }
            // w tym samym oknie co oferta (strict) przycisk nie ma ceny; w nowym oknie potwierdzenia może mieć
            if (strict && PriceParser.findPrice(loreLines(st), pp).isPresent()) {
                continue;
            }
            return slot;
        }
        // 3) krótkie słowa ("kup", "tak") - tylko w osobnym oknie potwierdzenia
        if (!strict) {
            List<String> loose = new ArrayList<>();
            for (String k : cfg.confirmButtonKeywords) {
                if (norm(k).length() <= 3) {
                    loose.add(k);
                }
            }
            if (!loose.contains("kup")) loose.add("kup");
            if (!loose.contains("tak")) loose.add("tak");
            return findByName(handler, containerSlots, loose);
        }
        return null;
    }

    /** Dopasowanie po całych członach id ("lime" pasuje do lime_concrete, ale nie do slime_ball). */
    private static boolean idMatches(String id, List<String> keywords) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String[] parts = path.split("_");
        for (String k : keywords) {
            String kk = k.toLowerCase(Locale.ROOT).trim();
            if (kk.isEmpty()) {
                continue;
            }
            if (kk.contains("_")) {
                if (path.equals(kk) || path.startsWith(kk + "_") || path.endsWith("_" + kk) || path.contains("_" + kk + "_")) {
                    return true;
                }
            } else {
                for (String part : parts) {
                    if (part.equals(kk)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Cena pokazana w oknie potwierdzenia (tylko linie opisane jako "Cena"/"Koszt"); null = nie widać. */
    private static PriceParser.Found confirmWindowPrice(class_1703 handler, int containerSlots, AutoRynekConfig cfg) {
        Pattern pp = cfg.pricePattern();
        for (int i = 0; i < containerSlots; i++) {
            class_1799 st = handler.field_7761.get(i).method_7677();
            if (st.method_7960()) {
                continue;
            }
            PriceParser.Found f = PriceParser.findLabelled(loreLines(st), pp);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static boolean containsAny(String normalizedText, List<String> keywords) {
        for (String k : keywords) {
            String nk = norm(k);
            if (!nk.isEmpty() && normalizedText.contains(nk)) {
                return true;
            }
        }
        return false;
    }

    private static void click(class_310 client, class_1703 handler, class_1735 slot, AutoRynekConfig cfg) {
        class_1713 type = "QUICK_MOVE".equalsIgnoreCase(cfg.clickMode) ? class_1713.field_7794 : class_1713.field_7790;
        client.field_1761.method_2906(handler.field_7763, slot.field_7874, 0, type, client.field_1724);
    }

    /** Normalizacja tekstu: bez kodów kolorów, bez polskich znaków, małe litery. */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("§.", "");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.replace('ł', 'l').replace('Ł', 'L');
        return t.toLowerCase(Locale.ROOT).trim();
    }

    private static void chat(class_746 player, String message, class_124 color) {
        player.method_7353(class_2561.method_43470("[AutoRynek] " + message).method_27692(color), false);
    }

    /** Zrzut zawartości aktualnie otwartego okna skrzyni do logu - pomaga dopasować konfigurację do serwera. */
    private void dumpDiagnostics(class_310 client, class_437 gcs, AutoRynekConfig cfg) {
        class_1703 handler = ((class_465<?>) gcs).method_17577();
        int containerSlots = containerSlotCount(handler);
        Pattern p = cfg.pricePattern();
        String title = gcs.method_25440().getString();
        AutoRynekClient.LOGGER.info("[diagnoza] tytuł okna='{}', sloty skrzyni={}", title, containerSlots);
        int nonEmpty = 0;
        int withPrice = 0;
        for (int i = 0; i < containerSlots; i++) {
            class_1799 st = handler.field_7761.get(i).method_7677();
            if (st.method_7960()) {
                continue;
            }
            nonEmpty++;
            List<String> lore = loreLines(st);
            OptionalDouble price = PriceParser.findPrice(lore, p);
            if (price.isPresent()) {
                withPrice++;
            }
            AutoRynekClient.LOGGER.info("[diagnoza] slot {}: nazwa='{}' x{} | opis={} | cena={}",
                    i, st.method_7964().getString(), st.method_7947(), lore,
                    price.isPresent() ? PriceParser.format(price.getAsDouble()) : "nie rozpoznano");
        }
        if (client.field_1724 != null) {
            chat(client.field_1724, "Diagnoza: tytuł okna \"" + title + "\", niepustych slotów: " + nonEmpty
                    + ", z rozpoznaną ceną: " + withPrice + ". Szczegóły w logu gry (logs/latest.log).", class_124.field_1054);
            boolean titleOk = norm(title).contains(norm(cfg.marketTitleContains));
            class_1735 sortSlotFound = findSortSlot(handler, containerSlots, cfg);
            chat(client.field_1724, sortSlotFound != null
                            ? "Przycisk sortowania: slot " + sortSlotFound.field_7874 + (sortIsTarget(loreLines(sortSlotFound.method_7677()), cfg) ? " (ustawione: " + cfg.sortTarget + ")." : " (inna opcja niż " + cfg.sortTarget + ").")
                            : "Nie wykryto przycisku sortowania - ustaw sortSlot w pliku konfiguracji.",
                    sortSlotFound != null ? class_124.field_1060 : class_124.field_1061);
            class_1735 axeSlot = findRefreshSlot(handler, containerSlots, cfg);
            chat(client.field_1724, axeSlot != null
                            ? "Siekierka odświeżania: slot " + axeSlot.field_7874 + "."
                            : "Nie wykryto siekierki odświeżania - ustaw refreshSlot w pliku konfiguracji.",
                    axeSlot != null ? class_124.field_1060 : class_124.field_1061);
            chat(client.field_1724, titleOk
                            ? "Tytuł pasuje do ustawienia marketTitleContains."
                            : "Tytuł NIE zawiera \"" + cfg.marketTitleContains + "\" - popraw marketTitleContains w pliku konfiguracji.",
                    titleOk ? class_124.field_1060 : class_124.field_1061);
        }
    }
}
