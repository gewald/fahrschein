package org.zalando.fahrschein;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.collect.Iterables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.zalando.fahrschein.domain.Batch;
import org.zalando.fahrschein.domain.Cursor;
import org.zalando.fahrschein.domain.Subscription;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.singletonList;

public class NakadiReader<T> {

    private static final Logger LOG = LoggerFactory.getLogger(NakadiReader.class);

    private final URI uri;
    private final ClientHttpRequestFactory clientHttpRequestFactory;
    private final ExponentialBackoffStrategy exponentialBackoffStrategy;
    private final CursorManager cursorManager;

    private final ObjectMapper objectMapper;

    private final String eventName;
    private final Optional<Subscription> subscription;
    private final Class<T> eventClass;
    private final Listener<T> listener;

    public NakadiReader(URI uri, ClientHttpRequestFactory clientHttpRequestFactory, ExponentialBackoffStrategy exponentialBackoffStrategy, CursorManager cursorManager, ObjectMapper objectMapper, String eventName, Optional<Subscription> subscription, Class<T> eventClass, Listener<T> listener) {
        this.uri = uri;
        this.clientHttpRequestFactory = clientHttpRequestFactory;
        this.exponentialBackoffStrategy = exponentialBackoffStrategy;
        this.cursorManager = cursorManager;
        this.objectMapper = objectMapper;
        this.eventName = eventName;
        this.subscription = subscription;
        this.eventClass = eventClass;
        this.listener = listener;

        checkState(!subscription.isPresent() || eventName.equals(Iterables.getOnlyElement(subscription.get().getEventTypes())));
    }

    private JsonParser open(JsonFactory jsonFactory, int errorCount) throws IOException, InterruptedException, ExponentialBackoffException {
        return exponentialBackoffStrategy.call(errorCount, () -> open0(jsonFactory));
    }

    private JsonParser open0(final JsonFactory jsonFactory) throws IOException, InterruptedException {
        final ClientHttpRequest request = clientHttpRequestFactory.createRequest(uri, HttpMethod.GET);
        if (!subscription.isPresent()) {
            final Collection<Cursor> cursors = cursorManager.getCursors(eventName);
            if (!cursors.isEmpty()) {
                final String value = objectMapper.writeValueAsString(cursors);
                request.getHeaders().put("X-Nakadi-Cursors", singletonList(value));
            }
        }
        final ClientHttpResponse response = request.execute();

        return jsonFactory.createParser(response.getBody());
    }

    private void processBatch(Batch<T> batch) throws EventProcessingException {
        final Cursor cursor = batch.getCursor();
        try {
            listener.onEvent(batch.getEvents());
            cursorManager.onSuccess(eventName, cursor);
        } catch (EventProcessingException e) {
            cursorManager.onError(eventName, cursor, e);
            throw e;
        }
    }

    private Cursor readCursor(JsonParser jsonParser) throws IOException {
        String partition = null;
        String offset = null;

        final JsonToken token = jsonParser.nextToken();
        checkState(token == JsonToken.START_OBJECT);

        while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
            String field = jsonParser.getCurrentName();
            switch (field) {
                case "partition":
                    partition = jsonParser.nextTextValue();
                    break;
                case "offset":
                    offset = jsonParser.nextTextValue();
                    break;
                default:
                    LOG.warn("Unexpected field [{}] in cursor", field);
                    jsonParser.nextToken();
                    jsonParser.skipChildren();
                    break;
            }
        }

        if (partition == null) {
            throw new IllegalStateException("Could not read partition from cursor");
        }
        if (offset == null) {
            throw new IllegalStateException("Could not read offset from cursor for partition [" + partition + "]");
        }

        return new Cursor(partition, offset);
    }

    private List<T> readEvents(ObjectReader objectReader, JsonParser jsonParser) throws IOException {
        final JsonToken token = jsonParser.nextToken();
        checkState(token == JsonToken.START_ARRAY);

        final List<T> events = new ArrayList<>();

        while (jsonParser.nextToken() == JsonToken.START_OBJECT) {
            final T event = objectReader.readValue(jsonParser, eventClass);
            events.add(event);
        }

        return events;
    }

    public void run() throws IOException, ExponentialBackoffException {

        final JsonFactory jsonFactory = objectMapper.copy().getFactory().configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
        final ObjectReader objectReader = objectMapper.reader().forType(eventClass);

        JsonParser jsonParser;

        try {
            jsonParser = open(jsonFactory, 0);
        } catch (InterruptedException e) {
            LOG.warn("Interrupted during initial connection");
            Thread.currentThread().interrupt();
            return;
        }


        int errorCount = 0;

        while (true) {
            try {
                JsonToken token;
                String field;

                token = jsonParser.nextToken();
                checkState(token == JsonToken.START_OBJECT, "Expected [%s] but got [%s]", JsonToken.START_OBJECT, token);
                field = jsonParser.nextFieldName();
                checkState("cursor".equals(field), "Expected [cursor] field but got [%s]", field);

                final Cursor cursor = readCursor(jsonParser);

                LOG.debug("Cursor for partition [{}] at offset [{}]", cursor.getPartition(), cursor.getOffset());

                token = jsonParser.nextToken();
                if (token != JsonToken.END_OBJECT) {
                    field = jsonParser.getCurrentName();
                    checkState("events".equals(field), "Expected [event] field but got [%s]", field);

                    final List<T> events = readEvents(objectReader, jsonParser);

                    token = jsonParser.nextToken();
                    checkState(token == JsonToken.END_OBJECT, "Expected [%s] but got [%s]", JsonToken.END_OBJECT, token);

                    final Batch<T> batch = new Batch<>(cursor, Collections.unmodifiableList(events));

                    processBatch(batch);

                }
                errorCount = 0;
            } catch (IOException e) {

                LOG.warn("Got [{}] while reading events", e.getClass().getSimpleName(), e);
                try {
                    LOG.debug("Trying to close input stream");
                    jsonParser.close();
                } catch (IOException e1) {
                    LOG.warn("Could not close input stream on IOException");
                }

                try {
                    LOG.info("Reconnecting after [{}] errors", errorCount);
                    jsonParser = open(jsonFactory, errorCount);
                } catch (InterruptedException e1) {
                    LOG.warn("Interrupted during reconnection");

                    Thread.currentThread().interrupt();
                    return;
                }

                errorCount++;
            }
        }
    }

}
