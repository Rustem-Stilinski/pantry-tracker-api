package com.usedlog.events;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live updates over Server-Sent Events. Each open browser tab holds one SseEmitter for its household;
 * when anyone adds or removes an entry, every tab in that household gets an "entries-changed" event
 * and re-fetches what it is showing.
 *
 * Emitters live in memory, so this works for a single backend instance. To run several instances,
 * fan events out through something shared (Postgres LISTEN/NOTIFY, Redis pub/sub, or SNS).
 */
@Component
public class HouseholdEvents {

    private static final Logger log = LoggerFactory.getLogger(HouseholdEvents.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID householdId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(householdId, id -> ConcurrentHashMap.newKeySet()).add(emitter);

        Runnable remove = () -> remove(householdId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());

        try {
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException ex) {
            remove.run();
        }
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEntryChanged(EntryChangedEvent event) {
        Set<SseEmitter> targets = emitters.get(event.householdId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        Message message = new Message(event.type().name().toLowerCase(Locale.ROOT), event.entryId(), event.usedAt());
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("entries-changed").data(message, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                remove(event.householdId(), emitter);
            }
        }
    }

    /** Keeps idle connections open through proxies and load balancers that drop silent streams. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.forEach((householdId, set) -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException ex) {
                remove(householdId, emitter);
            }
        }));
    }

    private void remove(UUID householdId, SseEmitter emitter) {
        emitters.computeIfPresent(householdId, (id, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
        log.debug("SSE connection closed for household {}", householdId);
    }

    /** JSON body of an "entries-changed" event. */
    public record Message(String type, UUID entryId, Instant usedAt) {
    }
}
