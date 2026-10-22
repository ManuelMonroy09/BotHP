package mx.jun.trading.paper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/** Atomic, fail-closed persistence for the virtual portfolio. */
public class PaperTradingStateStore {
    private static final String VERSION = "5";
    private static final String VERSION_WITH_EVENTS = "4";
    private static final String LEGACY_VERSION = "3";
    private static final int MAX_EVENTS = 100_000;
    private final Path statePath;

    public PaperTradingStateStore(Path statePath) {
        if (statePath == null || statePath.getFileName() == null) {
            throw new IllegalArgumentException("La ruta del estado paper no es válida");
        }
        this.statePath = statePath.toAbsolutePath().normalize();
    }

    public Optional<PaperTradingState> load() {
        assertNoSymlinkComponents(statePath);
        if (!Files.exists(statePath)) return Optional.empty();
        if (Files.isSymbolicLink(statePath)) {
            throw new IllegalStateException("El archivo de estado paper no puede ser un enlace simbólico");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(statePath)) {
            properties.load(input);
            PaperTradingState state = parse(properties);
            if (!VERSION.equals(properties.getProperty("version"))) save(state);
            return Optional.of(state);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("No se pudo leer el estado paper; se bloquea el inicio", e);
        }
    }

    public void save(PaperTradingState state) {
        validate(state);
        Path parent = statePath.getParent();
        Path temporary = null;
        try {
            assertNoSymlinkComponents(parent);
            Files.createDirectories(parent);
            assertNoSymlinkComponents(parent);
            temporary = Files.createTempFile(parent, ".paper-state-", ".tmp");
            Properties properties = serialize(state);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Virtual trading state; no credentials");
            }
            try {
                Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el estado paper; se bloquean nuevas entradas", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A leftover temporary file is never treated as authoritative state.
                }
            }
        }
    }

    private static PaperTradingState parse(Properties properties) {
        String version = required(properties, "version");
        if (!VERSION.equals(version) && !VERSION_WITH_EVENTS.equals(version)
                && !LEGACY_VERSION.equals(version)) {
            throw new IllegalArgumentException("Versión de estado paper no soportada");
        }
        BigDecimal cash = decimal(properties, "cash");
        BigDecimal quantity = decimal(properties, "quantity");
        BigDecimal entryPrice = optionalDecimal(properties, "entryPrice");
        BigDecimal stopPrice = optionalDecimal(properties, "stopPrice");
        Instant entryTime = optionalInstant(properties, "entryTime");
        Instant lastProcessedCandle = optionalInstant(properties, "lastProcessedCandle");
        BigDecimal referenceEquity = decimal(properties, "referenceEquity");
        BigDecimal peakEquity = decimal(properties, "peakEquity");
        BigDecimal dayStartEquity = decimal(properties, "dayStartEquity");
        String riskDayUtc = required(properties, "riskDayUtc");
        LocalDate.parse(riskDayUtc);
        List<PaperEvent> eventHistory = VERSION.equals(version) || VERSION_WITH_EVENTS.equals(version)
                ? parseEvents(properties) : java.util.List.of();
        PaperTradingState state = new PaperTradingState(cash, quantity, entryPrice, stopPrice,
                entryTime, lastProcessedCandle, referenceEquity, peakEquity, dayStartEquity, riskDayUtc,
                bool(properties, "dailyLocked"), bool(properties, "cumulativeLocked"),
                bool(properties, "killSwitchLatched"), optionalDecimal(properties, "entryTotalCost"),
                decimal(properties, "totalFees"), decimal(properties, "realizedPnl"),
                integer(properties, "closedTrades"), integer(properties, "winningTrades"),
                integer(properties, "losingTrades"), integer(properties, "breakEvenTrades"), eventHistory,
                VERSION.equals(version) ? required(properties, "sessionProfile")
                        : PaperTradingState.LEGACY_UNBOUND_PROFILE);
        validate(state);
        return state;
    }

    private static Properties serialize(PaperTradingState state) {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        set(properties, "cash", state.cash());
        set(properties, "quantity", state.quantity());
        set(properties, "entryPrice", state.entryPrice());
        set(properties, "stopPrice", state.stopPrice());
        set(properties, "entryTime", state.entryTime());
        set(properties, "lastProcessedCandle", state.lastProcessedCandle());
        set(properties, "referenceEquity", state.referenceEquity());
        set(properties, "peakEquity", state.peakEquity());
        set(properties, "dayStartEquity", state.dayStartEquity());
        properties.setProperty("riskDayUtc", state.riskDayUtc());
        properties.setProperty("dailyLocked", Boolean.toString(state.dailyLocked()));
        properties.setProperty("cumulativeLocked", Boolean.toString(state.cumulativeLocked()));
        properties.setProperty("killSwitchLatched", Boolean.toString(state.killSwitchLatched()));
        set(properties, "entryTotalCost", state.entryTotalCost());
        set(properties, "totalFees", state.totalFees());
        set(properties, "realizedPnl", state.realizedPnl());
        properties.setProperty("closedTrades", Integer.toString(state.closedTrades()));
        properties.setProperty("winningTrades", Integer.toString(state.winningTrades()));
        properties.setProperty("losingTrades", Integer.toString(state.losingTrades()));
        properties.setProperty("breakEvenTrades", Integer.toString(state.breakEvenTrades()));
        properties.setProperty("eventCount", Integer.toString(state.eventHistory().size()));
        properties.setProperty("sessionProfile", state.sessionProfile());
        for (int i = 0; i < state.eventHistory().size(); i++) {
            PaperEvent event = state.eventHistory().get(i);
            String prefix = "event." + i + ".";
            set(properties, prefix + "timestamp", event.timestamp());
            set(properties, prefix + "type", event.type());
            set(properties, prefix + "reason", event.reason());
            set(properties, prefix + "price", event.price());
            set(properties, prefix + "quantity", event.quantity());
            set(properties, prefix + "fee", event.fee());
            set(properties, prefix + "equity", event.equity());
            set(properties, prefix + "realizedPnl", event.realizedPnl());
        }
        return properties;
    }

    private static java.util.List<PaperEvent> parseEvents(Properties properties) {
        int count = integer(properties, "eventCount");
        if (count < 0 || count > MAX_EVENTS) {
            throw new IllegalArgumentException("Cantidad de eventos paper inválida");
        }
        var events = new java.util.ArrayList<PaperEvent>(count);
        for (int i = 0; i < count; i++) {
            String prefix = "event." + i + ".";
            events.add(new PaperEvent(Instant.parse(required(properties, prefix + "timestamp")),
                    required(properties, prefix + "type"), required(properties, prefix + "reason"),
                    decimal(properties, prefix + "price"), decimal(properties, prefix + "quantity"),
                    decimal(properties, prefix + "fee"), decimal(properties, prefix + "equity"),
                    decimal(properties, prefix + "realizedPnl")));
        }
        return java.util.List.copyOf(events);
    }

    private static void validate(PaperTradingState state) {
        if (state == null || state.cash() == null || state.cash().signum() < 0
                || state.quantity() == null || state.quantity().signum() < 0
                || state.referenceEquity() == null || state.referenceEquity().signum() <= 0
                || state.peakEquity() == null || state.peakEquity().signum() <= 0
                || state.dayStartEquity() == null || state.dayStartEquity().signum() <= 0
                || state.riskDayUtc() == null || state.totalFees() == null || state.totalFees().signum() < 0
                || state.realizedPnl() == null || state.closedTrades() < 0 || state.winningTrades() < 0
                || state.losingTrades() < 0 || state.breakEvenTrades() < 0
                || state.eventHistory() == null || state.eventHistory().size() > MAX_EVENTS
                || state.sessionProfile() == null || state.sessionProfile().isBlank()
                || state.sessionProfile().length() > 256
                || state.closedTrades() != state.winningTrades() + state.losingTrades() + state.breakEvenTrades()) {
            throw new IllegalArgumentException("Estado paper incompleto o inválido");
        }
        for (PaperEvent event : state.eventHistory()) {
            if (event == null || event.timestamp() == null || event.type() == null || event.type().isBlank()
                    || event.reason() == null || event.reason().isBlank() || event.price() == null
                    || event.price().signum() <= 0 || event.quantity() == null || event.quantity().signum() < 0
                    || event.fee() == null || event.fee().signum() < 0 || event.equity() == null
                    || event.equity().signum() < 0 || event.realizedPnl() == null) {
                throw new IllegalArgumentException("Historial de eventos paper inválido");
            }
        }
        LocalDate.parse(state.riskDayUtc());
        if (state.hasPosition()) {
            if (state.entryPrice() == null || state.entryPrice().signum() <= 0
                    || state.stopPrice() == null || state.stopPrice().signum() <= 0
                    || state.stopPrice().compareTo(state.entryPrice()) >= 0
                    || state.entryTime() == null || state.entryTotalCost() == null
                    || state.entryTotalCost().signum() <= 0) {
                throw new IllegalArgumentException("La posición paper no tiene datos completos de entrada/stop");
            }
        } else if (state.entryPrice() != null || state.stopPrice() != null || state.entryTime() != null
                || state.entryTotalCost() != null) {
            throw new IllegalArgumentException("Hay datos de posición paper aunque la cantidad sea cero");
        }
    }

    private static void assertNoSymlinkComponents(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("La ruta del estado paper contiene un enlace simbólico");
            }
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Falta " + key);
        return value.trim();
    }

    private static BigDecimal decimal(Properties properties, String key) {
        return new BigDecimal(required(properties, key));
    }

    private static int integer(Properties properties, String key) {
        return Integer.parseInt(required(properties, key));
    }

    private static BigDecimal optionalDecimal(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? null : new BigDecimal(value);
    }

    private static Instant optionalInstant(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? null : Instant.parse(value);
    }

    private static boolean bool(Properties properties, String key) {
        String value = required(properties, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("Boolean inválido en " + key);
        }
        return Boolean.parseBoolean(value);
    }

    private static void set(Properties properties, String key, Object value) {
        properties.setProperty(key, value == null ? "" : value.toString());
    }
}
