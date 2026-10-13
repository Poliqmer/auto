package pl.anarchia.autorynek;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.class_124;
import net.minecraft.class_2561;
import net.minecraft.class_7157;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoRynekClient implements ClientModInitializer {
    public static final String MOD_ID = "anarchia_autorynek";
    public static final Logger LOGGER = LoggerFactory.getLogger("AutoRynek");

    private static volatile boolean openMenuRequested = false;
    private final MarketScanner scanner = new MarketScanner();

    @Override
    public void onInitializeClient() {
        AutoRynekConfig.load();

        ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openMenuRequested) {
                openMenuRequested = false;
                client.method_1507(new AutoRynekScreen());
            }
            scanner.tick(client);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AutoRynekConfig.save());

        LOGGER.info("Anarchia AutoRynek załadowany. Menu: /autorynek");
    }

    private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, class_7157 registryAccess) {
        dispatcher.register(ClientCommandManager.literal("autorynek")
                .executes(ctx -> {
                    // Menu otwieramy w następnym ticku, bo po wykonaniu komendy czat sam zamyka swój ekran.
                    openMenuRequested = true;
                    return 1;
                })
                .then(ClientCommandManager.literal("on").executes(ctx -> {
                    AutoRynekConfig.get().enabled = true;
                    AutoRynekConfig.save();
                    ctx.getSource().sendFeedback(class_2561.method_43470("[AutoRynek] Włączono.").method_27692(class_124.field_1060));
                    return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(ctx -> {
                    AutoRynekConfig.get().enabled = false;
                    AutoRynekConfig.save();
                    ctx.getSource().sendFeedback(class_2561.method_43470("[AutoRynek] Wyłączono.").method_27692(class_124.field_1061));
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    AutoRynekConfig cfg = AutoRynekConfig.get();
                    ctx.getSource().sendFeedback(class_2561.method_43470("[AutoRynek] Status: " + (cfg.enabled ? "WŁĄCZONY" : "WYŁĄCZONY")
                            + ", pozycji na liście: " + cfg.entries.size()
                            + ", domyślna maks. cena: " + PriceParser.format(cfg.defaultMaxPrice)
                            + ", prób zakupu w tej sesji: " + scanner.purchaseAttempts()).method_27692(class_124.field_1054));
                    return 1;
                }))
                .then(ClientCommandManager.literal("diagnoza").executes(ctx -> {
                    scanner.requestDiagnostic();
                    ctx.getSource().sendFeedback(class_2561.method_43470("[AutoRynek] Diagnoza włączona: otwórz teraz rynek serwera"
                            + " (np. /" + AutoRynekConfig.get().marketCommand + "). Zrzut okna trafi do czatu i logu gry.")
                            .method_27692(class_124.field_1054));
                    return 1;
                }))
                .then(ClientCommandManager.literal("pomoc").executes(ctx -> {
                    ctx.getSource().sendFeedback(class_2561.method_43470("[AutoRynek] Komendy: /autorynek (menu), /autorynek on, /autorynek off,"
                            + " /autorynek status, /autorynek diagnoza").method_27692(class_124.field_1054));
                    return 1;
                })));
    }
}
