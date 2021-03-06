package com.nike.wingtips;

import com.nike.wingtips.lifecyclelistener.SpanLifecycleListener;
import com.nike.wingtips.sampling.RootSpanSamplingStrategy;
import com.nike.wingtips.sampling.SampleAllTheThingsStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * <p>
 *     Tracer implementation based on the Google Dapper paper:
 *     http://static.googleusercontent.com/media/research.google.com/en/us/pubs/archive/36356.pdf
 * </p>
 * <p>
 *     Instead using a data store to record the tracing information, SLF4J is used. The results will be logged and a separate process on the machine can be used to extract the
 *     tracing information from the logs and send it to a collector/data store/log aggregator/etc (if desired). This is consistent with the behavior described in the Google Dapper
 *     paper. Valid distributed trace spans will be logged to a SLF4J logger named {@value #VALID_DTRACE_SPAN_LOGGER_NAME} so you can pipe them to their own log file if desired.
 *     Similarly, invalid spans (where this class has detected incorrect usage and knows that the spans have invalid timing info, for example) will be logged to a SLF4J
 *     logger named {@value #INVALID_DTRACE_SPAN_LOGGER_NAME}. These specially-named loggers will not be used for any other purpose.
 * </p>
 * <p>
 *     Sampling is determined using {@link #rootSpanSamplingStrategy} which defaults to sampling everything. You can override this by calling
 *     {@link #setRootSpanSamplingStrategy(RootSpanSamplingStrategy)}.
 * </p>
 * <p>
 *     You can be notified of span lifecycle events (i.e. for metrics counting) by adding a listener to {@link #addSpanLifecycleListener(SpanLifecycleListener)}.
 *     NOTE: It's important that any {@link SpanLifecycleListener} you add is extremely lightweight or you risk distributed tracing becoming a major bottleneck for
 *     high throughput services. If any expensive work needs to be done in a {@link SpanLifecycleListener} then it should be done asynchronously on a thread or
 *     threadpool separate from the application worker threads.
 * </p>
 * <p>
 *     The format of the logging output when a span is completed is determined by {@link #spanLoggingRepresentation}, which can be set by calling
 *     {@link #setSpanLoggingRepresentation(SpanLoggingRepresentation)}. The default is {@link SpanLoggingRepresentation#JSON}, which causes the
 *     log messages to use {@link Span#toJSON()} to represent the span.
 * </p>
 * <p>
 *     The span information is associated with a thread and is modeled as a stack, so it's possible to have nested spans inside an overall request span. These nested spans are
 *     referred to as "sub-spans" in this class. Sub-spans are started via {@link #startSubSpan(String)} and completed via {@link #completeSubSpan()}. See the recommended usage
 *     section below for more information.
 * </p>
 * <p>
 *     <b>RECOMMENDED USAGE:</b> In a typical usage scenario you'll want to call one of the {@code startRequest...()} methods as soon as possible when a request enters your
 *     application, and you'll want to call {@link #completeRequestSpan()} as late as possible in the request/response cycle (ideally after the last of the response has been
 *     sent to the user, although some frameworks don't let you hook in that late). In between these two calls the span that was started (the "overall-request-span")
 *     is considered the "current span" for this thread and can be retrieved if necessary by calling {@link #getCurrentSpan()}.
 *     <br/>
 *     NOTE: Given the thread-local nature of this class you'll want to make sure the completion
 *     call is in a finally block or otherwise guaranteed to be called no matter what (even if the request fails with an error) to prevent problems when subsequent requests
 *     are processed on the same thread. This class does its best to recover from incorrect thread usage scenarios and log information about what happened
 *     but the best solution is to prevent the problems from occurring in the first place.
 * </p>
 * <p>
 *     The "request span" described above is intended to track the work done for the overall request. If you have work inside the request that you want tracked as a
 *     separate nested span with the overall-request-span as its parent you can do so via the "sub-span" methods: {@link #startSubSpan(String)} and {@link #completeSubSpan()}.
 *     These nested sub-spans are pushed onto the span stack associated with the current thread and you can have them as deeply nested as you want, but just like
 *     with the overall-request-span you'll want to make sure the completion method is called in a finally block or otherwise guaranteed to be executed even if an error occurs.
 *     Each call to {@link #startSubSpan(String)} causes the "current span" to be the new sub-span's parent, and causes the new sub-span to become the "current span" by
 *     pushing it onto the span stack. Each call to {@link #completeSubSpan()} does the reverse by popping the current span off the span stack and completing and logging it,
 *     thus causing its parent to become the current span again.
 * </p>
 * <p>
 *     One common use case for sub-spans is to track downstream calls separately from the overall request (e.g. HTTP calls to another service, database calls, or any other
 *     call that crosses network or application boundaries). Start a sub-span immediately before a downstream call and complete it immediately after the downstream call
 *     returns. You can inspect the sub-span to see how long the downstream call took from this application's point of view. If you do this around all your downstream calls
 *     you can subtract the total time spent for all downstream calls from the time spent for the overall-request-span to determine time spent in this application vs. time
 *     spent waiting for downstream calls to finish. And if the downstream service also performs distributed tracing and has an overall-request-span for its service call
 *     then you can subtract the downstream service's request-span time from this application's sub-span time around the downstream call to determine how much time was
 *     lost to network lag or any other bottlenecks between the services.
 * </p>
 * <p>
 *     In addition to the logs this class outputs for spans it puts the trace ID and span JSON for the "current" span into the SLF4J
 *     <a href="http://www.slf4j.org/manual.html#mdc">MDC</a> so that all your logs can be tagged with the current span's trace ID and/or full JSON. To utilize this you
 *     would need to add {@code %X{traceId}} and/or {@code %X{spanJson}} to your log pattern (NOTE: this only works with SLF4J frameworks that support MDC, e.g. logback
 *     and log4j). This causes *all* log messages, including ones that come from third party libraries and have no knowledge of distributed tracing, to be output with the
 *     current span's tracing information.
 * </p>
 * <p>
 *     NOTE: Due to the thread-local nature of this class it is more effort to integrate with reactive (asynchronous non-blocking) frameworks like Netty or actor frameworks
 *     than with thread-per-request frameworks. But it is not terribly difficult and the benefit of having all your log messages tagged with tracing information is worth
 *     the effort. This class provides the following methods to help integrate with reactive frameworks:
 *     <ul>
 *         <li>{@link #registerWithThread(Deque)}</li>
 *         <li>{@link #unregisterFromThread()}</li>
 *         <li>{@link #getCurrentSpanStackCopy()}</li>
 *     </ul>
 *     See the javadocs on those methods for more detailed information, but the general pattern would be to call the {@link #registerWithThread(Deque)} with the request's
 *     span stack whenever a thread starts to do some chunk of work for that request, and call {@link #unregisterFromThread()} when that chunk of work is done and the
 *     thread is about to be freed up to work on a different request. The span stack would need to follow the request no matter what thread was processing it,
 *     but assuming you can solve that problem in a reactive framework then the general pattern should work fine.
 * </p>
 *
 * @author Nic Munroe
 * @author Robert Roeser
 */
@SuppressWarnings("WeakerAccess")
public class Tracer {

    /**
     * The options for how {@link Span} objects are represented when they are completed. To change how {@link Tracer} serializes spans when logging them
     * call {@link #setSpanLoggingRepresentation(SpanLoggingRepresentation)}.
     */
    @SuppressWarnings("WeakerAccess")
    public enum SpanLoggingRepresentation {
        /**
         * Causes spans to be output in the logs using {@link Span#toJSON()}.
         */
        JSON,
        /**
         * Causes spans to be output in the logs using {@link Span#toKeyValueString()}.
         */
        KEY_VALUE
    }

    private static final String VALID_DTRACE_SPAN_LOGGER_NAME = "VALID_DTRACE_SPANS";
    private static final String INVALID_DTRACE_SPAN_LOGGER_NAME = "INVALID_DTRACE_SPANS";

    private static final Logger classLogger = LoggerFactory.getLogger(Tracer.class);
    private static final Logger validSpanLogger = LoggerFactory.getLogger(VALID_DTRACE_SPAN_LOGGER_NAME);
    private static final Logger invalidSpanLogger = LoggerFactory.getLogger(INVALID_DTRACE_SPAN_LOGGER_NAME);

    /**
     * ThreadLocal that keeps track of the stack of {@link Span} objects associated with the thread. This is treated as a LIFO stack.
     */
    private static final ThreadLocal<Deque<Span>> currentSpanStackThreadLocal = new ThreadLocal<>();

    /**
     * The singleton instance for this class.
     */
    private static final Tracer INSTANCE = new Tracer();

    /**
     * MDC key for storing the current {@link Span} as a JSON string.
     */
    public static final String SPAN_JSON_MDC_KEY = "spanJson";
    /**
     * MDC key for storing the current span's {@link Span#getTraceId()}.
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";


    /**
     * The sampling strategy this instance will use. Default to sampling everything. Never allow this field to be set to null.
     */
    private RootSpanSamplingStrategy rootSpanSamplingStrategy = new SampleAllTheThingsStrategy();

    /**
     * The list of span lifecycle listeners that should be notified when span lifecycle events occur.
     */
    private final List<SpanLifecycleListener> spanLifecycleListeners = new ArrayList<>();

    /**
     * The span representation that should be used when logging completed spans.
     */
    private SpanLoggingRepresentation spanLoggingRepresentation = SpanLoggingRepresentation.JSON;

    private Tracer() { /* Intentionally private to enforce singleton pattern. */ }

    /**
     * @return The singleton instance of this class.
     */
    public static Tracer getInstance() {
        return INSTANCE;
    }

    /**
     * The {@link Span} set as the "current" one for this thread.
     * <p/>
     * NOTE: If {@link #currentSpanStackThreadLocal} is null or empty for this thread it will try to reconstitute the {@link Span} from the logging {@link org.slf4j.MDC}.
     * This is useful in some situations, for example async request processing where the thread changes but the MDC is smart enough to transfer the span anyway.
     * In any case as a caller you don't have to care - you'll just get the {@link Span} appropriate for the caller, or null if one hasn't been set up yet.
     */
    public Span getCurrentSpan() {
        Deque<Span> spanStack = currentSpanStackThreadLocal.get();
        if (spanStack == null || spanStack.isEmpty()) {
            setCurrentSpanFromMdcIfNotAlreadySet();
            spanStack = currentSpanStackThreadLocal.get();
        }

        return (spanStack == null) ? null : spanStack.peek();
    }

    /**
     * Starts new span stack (i.e. new incoming request) without a parent span by creating a root span with the given span name.
     * If you have parent span info then you should call {@link #startRequestWithChildSpan(Span, String)} or
     * {@link #startRequestWithSpanInfo(String, String, String, boolean, String)} instead).
     * <p/>
     * NOTE: This method will cause the returned span's {@link Span#getUserId()} to contain null. If you have a user ID you should use
     * {@link #startRequestWithRootSpan(String, String)} instead.
     * <p/>
     * <b>WARNING:</b> This wipes out any existing spans on the span stack for this thread and starts fresh, therefore this should only be called at the request's
     * entry point when it's expected that the span stack should be empty. If you need to start a child span in the middle of a request somewhere then you should call
     * {@link #startSubSpan(String)} instead.
     *
     * @param spanName - The span name to use for the new span - should never be null.
     * @return The new span (which is now also the current one that will be returned by {@link #getCurrentSpan()}).
     */
    public Span startRequestWithRootSpan(String spanName) {
        return startRequestWithRootSpan(spanName, null);
    }

    /**
     * Similar to {@link #startRequestWithRootSpan(String)} but takes in an optional {@code userId} to populate the returned {@link Span#getUserId()}.
     * If {@code userId} is null then this method is equivalent to calling {@link #startRequestWithRootSpan(String)}. If you have parent span info then you should call
     * {@link #startRequestWithChildSpan(Span, String)} or {@link #startRequestWithSpanInfo(String, String, String, boolean, String)} instead).
     * <p/>
     * <b>WARNING:</b> This wipes out any existing spans on the span stack for this thread and starts fresh, therefore this should only be called at the request's
     * entry point when it's expected that the span stack should be empty. If you need to start a child span in the middle of a request somewhere then you should call
     * {@link #startSubSpan(String)} instead.
     *
     * @param spanName - The span name to use for the new span - should never be null.
     * @param userId - The ID of the user that should be associated with the {@link Span} - can be null.
     * @return The new span (which is now also the current one that will be returned by {@link #getCurrentSpan()}).
     */
    public Span startRequestWithRootSpan(String spanName, String userId) {
        boolean sampleable = isNextRootSpanSampleable();
        String traceId = TraceAndSpanIdGenerator.generateId();
        return doNewRequestSpan(traceId, null, spanName, sampleable, userId);
    }

    /**
     * Starts a new span stack (i.e. new incoming request) with the given span as the new child span's parent (if you do not have a parent then you should call
     * {@link #startRequestWithRootSpan(String)} or {@link #startRequestWithRootSpan(String, String)} instead).
     * <p/>
     * <b>WARNING:</b> This wipes out any existing spans on the span stack for this thread and starts fresh, therefore this should only be called at the request's
     * entry point when it's expected that the span stack should be empty. If you need to start a child span in the middle of a request somewhere then you should call
     * {@link #startSubSpan(String)} instead.
     *
     * @param parentSpan The span to use as the parent span - should never be null (if you need to start a span without a parent then call
     *                   {@link #startRequestWithRootSpan(String)} instead).
     * @param childSpanName The span name to use for the new child span - should never be null.
     * @return The new child span (which is now also the current one that will be returned by {@link #getCurrentSpan()}).
     */
    public Span startRequestWithChildSpan(Span parentSpan, String childSpanName) {
        if (parentSpan == null) {
            throw new IllegalArgumentException("parentSpan cannot be null. " +
                            "If you don't have a parent span then you should call one of the startRequestWithRootSpan(...) methods instead.");
        }

        return startRequestWithSpanInfo(parentSpan.getTraceId(), parentSpan.getSpanId(), childSpanName, parentSpan.isSampleable(), parentSpan.getUserId());
    }

    /**
     * Starts a new span stack (i.e. new incoming request) with the given span info used to create the new span. This method is agnostic on whether the span is a root span
     * or child span - the arguments you pass in (specifically the value of {@code parentSpanId}) will determine root vs child. The other {@code startRequestWith...()} methods
     * should be preferred to this where it makes sense, but if your use case doesn't match those methods then this method allows maximum flexibility in creating a span for
     * a new request.
     * <p/>
     * <b>WARNING:</b> This wipes out any existing spans on the span stack for this thread and starts fresh, therefore this should only be called at the request's
     * entry point when it's expected that the span stack should be empty. If you need to start a child span in the middle of a request somewhere then you should call
     * {@link #startSubSpan(String)} instead.
     *
     * @param traceId The new span's {@link Span#getTraceId()} - if this is null then a new random trace ID will be generated for the returned span. If you have parent span info
     *                then this should be the parent span's <b>trace</b> ID (not the parent span's span ID).
     * @param parentSpanId The parent span's {@link Span#getSpanId()} - if this is null then the returned {@link Span#getParentSpanId()} will be null causing the returned span
     *                     to be a root span. If it's non-null then the returned span will be a child span with {@link Span#getParentSpanId()} matching this value.
     * @param newSpanName The span name to use for the new span - should never be null.
     * @param sampleable The new span's {@link Span#isSampleable()} value. Determines whether or not this span should be logged when it is completed.
     * @param userId The new span's {@link Span#getUserId()} value.
     *
     * @return The new span (which is now also the current one that will be returned by {@link #getCurrentSpan()}).
     */
    public Span startRequestWithSpanInfo(String traceId, String parentSpanId, String newSpanName, boolean sampleable, String userId) {
        return doNewRequestSpan(traceId, parentSpanId, newSpanName, sampleable, userId);
    }

    /**
     * Starts a new child sub-span using the {@link #getCurrentSpan()} current span as its parent and pushes this new sub-span onto the span stack so that it becomes the
     * current span. When this new child sub-span is completed using {@link #completeSubSpan()} then it will be popped off the span stack and its parent will once again
     * become the current span.
     * <p/>
     * <b>WARNING:</b> This does *NOT* wipe out any existing spans on the span stack - it pushes a new one onto the stack. If you're calling from a spot in the code where
     * you know there should never be any existing spans on the thread's span stack (i.e. new incoming request) you should call one of the {@code startRequest...()} methods
     * instead.
     *
     * @param spanName The {@link Span#getSpanName()} to use for the new child sub-span.
     * @return The new child sub-span (which is now also the current one that will be returned by {@link #getCurrentSpan()}).
     */
    public Span startSubSpan(String spanName) {
        Span parentSpan = getCurrentSpan();
        if (parentSpan == null) {
            classLogger.error(
                    "DTRACE ERROR - Expected getCurrentSpan() to return a span for use as a parent for a new child sub-span but null was returned instead. This probably " +
                    "means the request's overall span was never started. The child sub-span will still be started without any parent. dtracer_error=true bad_span_stack=true",
                    new Exception("from here")
            );
        }

        Span childSpan = (parentSpan != null)
                ? parentSpan.generateChildSpan(spanName)
                : Span.generateRootSpanForNewTrace(spanName).withSampleable(isNextRootSpanSampleable()).build();

        pushSpanOntoCurrentSpanStack(childSpan);

        notifySpanStarted(childSpan);
        notifyIfSpanSampled(childSpan);

        return childSpan;
    }

    /**
     * Helper method that starts a new span for a fresh request.
     * <p/>
     * <b>WARNING:</b> This wipes out any existing spans on the span stack for this thread and starts fresh, therefore this should only be called at the request's
     * entry point when it's expected that the span stack should be empty. If you need to start a child span in the middle of a request somewhere then you should call
     * {@link #startSubSpan(String)} instead.
     *
     * @param traceId The new span's {@link Span#getTraceId()} - if this is null then a new random trace ID will be generated for the returned span. If you have parent span info
     *                then this should be the parent span's <b>trace</b> ID (not the parent span's span ID).
     * @param parentSpanId The parent span's {@link Span#getSpanId()} - if this is null then the returned {@link Span#getParentSpanId()} will be null causing the returned span
     *                     to be a root span. If it's non-null then the returned span will be a child span with {@link Span#getParentSpanId()} matching this value.
     * @param newSpanName The span name to use for the new span - should never be null.
     * @param sampleable The new span's {@link Span#isSampleable()} value. Determines whether or not this span should be logged when it is completed.
     * @param userId The new span's {@link Span#getUserId()} value.
     *
     * @return The new span (which is now also the current one that will be returned by {@link #getCurrentSpan()}).
     */
    protected Span doNewRequestSpan(String traceId, String parentSpanId, String newSpanName, boolean sampleable, String userId) {
        if (newSpanName == null)
            throw new IllegalArgumentException("spanName cannot be null");

        Span span = Span.newBuilder(newSpanName).withTraceId(traceId).withParentSpanId(parentSpanId).withSampleable(sampleable).withUserId(userId).build();

        // Since this is a "starting from scratch/new request" call we clear out and restart the current span stack even if it already had something in it.
        startNewSpanStack(span);

        notifySpanStarted(span);
        notifyIfSpanSampled(span);

        return span;
    }

    /**
     * Helper method that starts a new span stack for a fresh request and sets it on {@link #currentSpanStackThreadLocal}. Since this is assuming a fresh request it expects
     * {@link #currentSpanStackThreadLocal} to have a clean/empty/null stack in it right now. If it has a non-empty stack then it will log an error and clear it out
     * so that the given {@code firstEntry} argument is the only thing that will be on the stack after this method call. Delegates to {@link #pushSpanOntoCurrentSpanStack(Span)}
     * to push the {@code firstEntry} onto the clean stack so it can handle the MDC and debug logging, etc.
     */
    protected void startNewSpanStack(Span firstEntry) {
        // Log an error if we don't have a null/empty existing stack.
        Deque<Span> existingStack = currentSpanStackThreadLocal.get();
        if (existingStack != null && !existingStack.isEmpty()) {
            boolean first = true;
            StringBuilder lostTraceIds = new StringBuilder();
            for (Span span : existingStack) {
                if (!first)
                    lostTraceIds.append(',');
                lostTraceIds.append(span.getTraceId());
                first = false;
            }
            classLogger.error("DTRACE ERROR - We were asked to start a new span stack (i.e. new request) but there was a stack already on this thread with {} old spans. " +
                    "This probably means completeRequestSpan() was not called on the previous request this thread handled. The old spans will be cleared out " +
                    "and lost. dtracer_error=true, dirty_span_stack=true, lost_trace_ids={}",
                    existingStack.size(), lostTraceIds.toString(), new Exception("from here")
            );

        }

        currentSpanStackThreadLocal.set(new LinkedList<Span>());
        pushSpanOntoCurrentSpanStack(firstEntry);
    }

    /**
     * Uses {@link #spanLoggingRepresentation} to decide how to serialize the given span, and then returns the result of the serialization.
     */
    protected String serializeSpanToDesiredStringRepresentation(Span span) {
        switch(spanLoggingRepresentation) {
            case JSON:
                return span.toJSON();
            case KEY_VALUE:
                return span.toKeyValueString();
            default:
                throw new IllegalStateException("Unknown span logging representation type: " + spanLoggingRepresentation);
        }
    }

    /**
     * Pushes the given span onto the {@link #currentSpanStackThreadLocal} stack. If the stack is null it will create a new one. Also pushes the span info into the logging
     * {@link org.slf4j.MDC} so it is available there.
     */
    protected void pushSpanOntoCurrentSpanStack(Span pushMe) {
        Deque<Span> currentStack = currentSpanStackThreadLocal.get();
        if (currentStack == null) {
            currentStack = new LinkedList<>();
            currentSpanStackThreadLocal.set(currentStack);
        }

        currentStack.push(pushMe);
        configureMDC(pushMe);
        classLogger.debug("** starting sample for span {}", serializeSpanToDesiredStringRepresentation(pushMe));
    }

    /**
     * Sets the current {@link Span} on {@link #currentSpanStackThreadLocal} by extracting and deserializing it from the logging {@link org.slf4j.MDC} <b>if and only if</b>
     * {@link #currentSpanStackThreadLocal} is null/empty <b>AND</b> the MDC contains the span JSON. If there's already a span on the span stack or MDC does not contain any
     * span JSON then this method will do nothing.
     */
    protected void setCurrentSpanFromMdcIfNotAlreadySet() {
        // We only want to try to pull it from the MDC if there's not already a span available.
        Deque<Span> spanStack = currentSpanStackThreadLocal.get();
        if (spanStack != null && !spanStack.isEmpty())
            return;

        // No span already on this thread. See if we can pull it from the logging MDC.
        String json = MDC.get(Tracer.SPAN_JSON_MDC_KEY);
        if (json == null)
            return;

        // We were able to find a span JSON in the MDC. Deserialize it and push it onto a new stack.
        Deque<Span> newStack = new LinkedList<>();
        newStack.add(Span.fromJSON(json));
        currentSpanStackThreadLocal.set(newStack);
    }

    /**
     * Completes the current span by calling {@link #completeAndLogSpan(Span, boolean)} on it, empties the MDC by calling{@link #unconfigureMDC()}, and clears out the
     * {@link #currentSpanStackThreadLocal} stack.
     * <p/>
     * This should be called by the overall request when the request is done. At the point this method is called there should just be one span left on the
     * {@link #currentSpanStackThreadLocal} stack - the overall request span. If there is more than 1 then that indicates a bug with the usage of this class where
     * a child span is created but not completed. If this error case is detected then and *all* spans will be logged/popped and an error message will be logged with
     * details on what went wrong.
     */
    public void completeRequestSpan() {
        Deque<Span> currentSpanStack = currentSpanStackThreadLocal.get();
        if (currentSpanStack != null) {
            // Keep track of data as we go in case we need to output an error (we should only have 1 span in the stack)
            int originalSize = currentSpanStack.size();
            StringBuilder badTraceIds = new StringBuilder();

            while (!currentSpanStack.isEmpty()) {
                // Get the next span on the stack.
                Span span = currentSpanStack.pop();

                // Check if it's a "bad" span (i.e. not the last).
                boolean isBadSpan = false;
                if (!currentSpanStack.isEmpty()) {
                    // There's still at least one more span, so this one is "bad".
                    isBadSpan = true;
                    if (badTraceIds.length() > 0)
                        badTraceIds.append(',');
                    badTraceIds.append(span.getTraceId());
                }

                completeAndLogSpan(span, isBadSpan);
            }

            // Output an error message if we had any bad spans.
            if (originalSize > 1) {
                classLogger.error(
                        "DTRACE ERROR - We were asked to fully complete a request span (i.e. end of the request) but there was more than one span on this thread's stack (" +
                        "{} total spans when there should only be one). This probably means completeSubSpan() was not called on child sub-span(s) this thread " +
                        "generated - they should always be in finally clauses or otherwise guaranteed to complete. The bad child sub-spans were logged but the total " +
                        "time spent on the bad child sub-spans will not be correct. dtracer_error=true, dirty_span_stack=true, bad_subspan_trace_ids={}",
                        originalSize, badTraceIds.toString(), new Exception("from here")
                );
            }
        }

        currentSpanStackThreadLocal.remove();
        unconfigureMDC();
    }

    /**
     * Completes the current child sub-span by calling {@link #completeAndLogSpan(Span, boolean)} on it and then {@link #configureMDC(Span)} on the sub-span's parent
     * (which becomes the new current span).
     * <p/>
     * <b>WARNING:</b> This only works if there are at least 2 spans in the {@link #currentSpanStackThreadLocal} stack - one for the child sub-span and one for the parent span.
     * If you're trying to complete the overall request's span you should be calling {@link #completeRequestSpan()} instead. If there are 0 or 1 spans on the stack then
     * this method will log an error and do nothing.
     */
    public void completeSubSpan() {
        Deque<Span> currentSpanStack = currentSpanStackThreadLocal.get();
        if (currentSpanStack == null || currentSpanStack.size() < 2) {
            int stackSize = (currentSpanStack == null) ? 0 : currentSpanStack.size();
            classLogger.error(
                    "DTRACE ERROR - Expected to find a child sub-span on the stack to complete, but the span stack was size {} instead (there should be at least 2 for " +
                    "this method to be able to find a child sub-span). dtracer_error=true, bad_span_stack=true",
                    stackSize, new Exception("from here")
            );
            // Nothing to do
            return;
        }

        // We have at least two spans. Pop off the child sub-span and complete/log it.
        Span subSpan = currentSpanStack.pop();
        completeAndLogSpan(subSpan, false);

        // Now configure the MDC with the new current span.
        configureMDC(currentSpanStack.peek());
    }

    /**
     * Calls {@link Span#complete()} to complete the span and logs it (but only if the span's {@link Span#isSampleable()} returns true). If the span is valid then it will
     * be logged to {@link #validSpanLogger}, and if it is invalid then it will be logged to {@link #invalidSpanLogger}.
     *
     * @param span The span to complete and log
     * @param containsIncorrectTimingInfo Pass in true if you know the given span contains incorrect timing information (e.g. a child sub-span that wasn't completed normally
     *                                    when it was supposed to have been completed), pass in false if the span's timing info is good. This affects how the span is logged.
     */
    protected void completeAndLogSpan(Span span, boolean containsIncorrectTimingInfo) {
        // Complete the span.
        span.complete();
        // Log the span if it was sampleable.
        if (span.isSampleable()) {
            String infoTag = containsIncorrectTimingInfo ? "[INCORRECT_TIMING] " : "";
            Logger loggerToUse = containsIncorrectTimingInfo ? invalidSpanLogger : validSpanLogger;
            loggerToUse.info("{}[DISTRIBUTED_TRACING] {}", infoTag, serializeSpanToDesiredStringRepresentation(span));
        }

        // Notify listeners.
        notifySpanCompleted(span);
    }

    /**
     * Sets the span variables on the MDC context.
     */
    protected static void configureMDC(Span span) {
        MDC.put(TRACE_ID_MDC_KEY, span.getTraceId());
        MDC.put(SPAN_JSON_MDC_KEY, span.toJSON());
    }

    /**
     * Removes the MDC parameters.
     */
    protected static void unconfigureMDC() {
        MDC.remove(TRACE_ID_MDC_KEY);
        MDC.remove(SPAN_JSON_MDC_KEY);
    }

    /**
     * Allows you to set the {@link #rootSpanSamplingStrategy} used by this instance. This will throw an {@link IllegalArgumentException} if you pass in null.
     */
    public void setRootSpanSamplingStrategy(RootSpanSamplingStrategy strategy) {
        if (strategy == null)
            throw new IllegalArgumentException("RootSpanSamplingStrategy cannot be null");

        this.rootSpanSamplingStrategy = strategy;
    }

    /**
     * Delegates to {@link #rootSpanSamplingStrategy}'s {@link RootSpanSamplingStrategy#isNextRootSpanSampleable()} method to determine whether the next root span should be
     * sampled.
     * <br/>
     * NOTE: This method is not deterministic - you may get a different response every time you call it. Therefore you should not call this method multiple times for the same
     * root span. Call it once and store the result for a given root span.
     *
     * @return true when the next root span should be sampled, false otherwise.
     */
    protected boolean isNextRootSpanSampleable() {
        return rootSpanSamplingStrategy.isNextRootSpanSampleable();
    }

    /**
     * Adds the given listener to the {@link #spanLifecycleListeners} list using {@link java.util.List#add(Object)}. This method will do nothing if you pass in null.
     * <p/>
     * <b>WARNING:</b> It's important that any {@link SpanLifecycleListener} you add is extremely lightweight or you risk distributed tracing becoming a major bottleneck for
     * high throughput services. If any expensive work needs to be done in a {@link SpanLifecycleListener} then it should be done asynchronously on a thread or threadpool
     * separate from the application worker threads.
     */
    public void addSpanLifecycleListener(SpanLifecycleListener listener) {
        if (listener != null)
            this.spanLifecycleListeners.add(listener);
    }

    /**
     * Returns the value of calling {@link java.util.List#remove(Object)} on {@link #spanLifecycleListeners}.
     */
    public boolean removeSpanLifecycleListener(SpanLifecycleListener listener) {
        //noinspection SimplifiableIfStatement
        if (listener == null)
            return false;

        return this.spanLifecycleListeners.remove(listener);
    }

    /**
     * @return The {@link #spanLifecycleListeners} list of span lifecycle listeners associated with this instance, wrapped in a {@link Collections#unmodifiableList(List)}
     *          to prevent direct modification. This will never return null.
     */
    public List<SpanLifecycleListener> getSpanLifecycleListeners() {
        return Collections.unmodifiableList(this.spanLifecycleListeners);
    }

    /**
     * @return The currently selected option for how spans will be serialized when they are completed and logged.
     */
    public SpanLoggingRepresentation getSpanLoggingRepresentation() {
        return spanLoggingRepresentation;
    }

    /**
     * Sets the option for how spans will be serialized when they are completed and logged.
     */
    public void setSpanLoggingRepresentation(SpanLoggingRepresentation spanLoggingRepresentation) {
        if (spanLoggingRepresentation == null)
            throw new IllegalArgumentException("spanLoggingRepresentation cannot be null.");

        this.spanLoggingRepresentation = spanLoggingRepresentation;
    }


    /**
     * Notifies all listeners that the given span was started using {@link SpanLifecycleListener#spanStarted(Span)}
     */
    protected void notifySpanStarted(Span span) {
        for (SpanLifecycleListener tll : spanLifecycleListeners) {
            tll.spanStarted(span);
        }
    }

    /**
     * Notifies all listeners that the given span was sampled using {@link SpanLifecycleListener#spanSampled(Span)}, <b>but only if the span's {@link Span#isSampleable()}
     * method returns true!</b> If the span is not sampleable then this method does nothing.
     */
    protected void notifyIfSpanSampled(Span span) {
        if (span.isSampleable()) {
            for (SpanLifecycleListener tll : spanLifecycleListeners) {
                tll.spanSampled(span);
            }
        }
    }

    /**
     * Notifies all listeners that the given span was completed using {@link SpanLifecycleListener#spanCompleted(Span)}
     */
    protected void notifySpanCompleted(Span span) {
        for (SpanLifecycleListener tll : spanLifecycleListeners) {
            tll.spanCompleted(span);
        }
    }

    /**
     * IMPORTANT: This method is not for typical thread-per-request application usage - most of the time you just want {@link #getCurrentSpan()}. This method is here to
     * facilitate complex asynchronous threading situations.
     * <p/>
     * Returns a *COPY* of the current span stack. A copy is returned instead of the original to prevent you from directly modifying the stack - you should use the start/complete
     * request span/sub-span methods to manipulate the stack. But sometimes you need to see what the stack looks like, or store it at a certain state so you can reconstitute
     * it later (you can sort of do this with {@link #unregisterFromThread()} which also returns the span stack, but that unregisters everything and sometimes you need to
     * store for later without interrupting current state).
     * <p/>
     * This method may return null or an empty stack, depending on its current state.
     */
    public Deque<Span> getCurrentSpanStackCopy() {
        Deque<Span> currentStack = currentSpanStackThreadLocal.get();
        if (currentStack == null)
            return null;

        return new LinkedList<>(currentStack);
    }

    /**
     * "Unregisters" the current span stack from this thread, removes span-related info from the logging MDC, and returns the span stack that was unregistered so it
     * can be stored and re-registered later (if desired). This is used in asynchronous projects/frameworks where multiple in-progress requests might be handled by the same thread
     * before any of the requests can finish (e.g. Netty, where the worker I/O threads might process a portion of request A, flip over to request B to do some work, then go back
     * to request A, etc). This method lets you unregister the tracing info from the current thread when it is about to switch to processing a different request so the tracing
     * info can be stored in some kind of request context state that follows the request, and won't pollute the thread's span stack and MDC info when the new request starts being
     * processed.
     * <br/>
     * When processing switches back to the original request, you can call {@link #registerWithThread(java.util.Deque)} to set the thread's span stack and MDC info back to the
     * way it was when you first called this unregister method for the original request.
     * <p/>
     * <b>WARNING:</b> This method should NOT be called if you're in an environment where a single thread is guaranteed to process a request from start to finish without jumping
     * to a different request in the middle. In that case just use the normal start and complete span methods and ignore this method.
     */
    public Deque<Span> unregisterFromThread() {
        Deque<Span> currentValue = currentSpanStackThreadLocal.get();
        currentSpanStackThreadLocal.remove();
        unconfigureMDC();
        return currentValue;
    }

    /**
     * @return true if the two given stacks contain the same spans in the same order, false otherwise.
     */
    protected boolean containsSameSpansInSameOrder(Deque<Span> stack, Deque<Span> other) {
        if (stack == other)
            return true;

        if (stack == null || other == null)
            return false;

        if (stack.size() != other.size())
            return false;

        Iterator<Span> stackIterator = stack.iterator();
        Iterator<Span> otherIterator = other.iterator();

        while (stackIterator.hasNext()) {
            Span t1 = stackIterator.next();
            Span t2 = otherIterator.next();

            if (t1 != t2) {
                // Not the same instance, and at least one is non-null.
                if (t1 == null || t2 == null)
                    return false;

                if (!t1.equals(t2))
                    return false;
            }
        }

        return true;
    }

    /**
     * "Registers" a *COPY* of the given span stack with this thread (sets up the ThreadLocal span stack with a copy of the given argument) and sets up the MDC appropriately
     * based on what you pass in. This is used in asynchronous projects/frameworks where multiple in-progress requests might be handled by the same thread
     * before any of the requests can finish (e.g. Netty, where the worker I/O threads might process a portion of request A, flip over to request B to do some work, then go back
     * to request A, etc). When the worker thread was about to stop working on the original request and move to a new one you would call {@link #unregisterFromThread()}
     * and store the result with some kind of context state that follows the original request, and when processing switches back to the original request you would call
     * this method to set the thread's span stack and MDC info back to the way it was so you could continue where you left off.
     * <p/>
     * In a properly functioning project/framework this thread would not have any span information in its stack when this method was called, so if there are any spans already
     * on this thread then this method will mark them as invalid, complete them, and log an appropriate error message before registering the stack passed into this method.
     * <p/>
     * NOTE: A *copy* of the given stack is registered so that changes to the stack you pass in don't affect the stack stored here. This prevents a host of subtle annoying bugs.
     * <p/>
     * <b>WARNING:</b> This method should NOT be called if you're in an environment where a single thread is guaranteed to process a request from start to finish without jumping
     * to a different request in the middle. In that case just use the normal start and complete span methods and ignore this method.
     */
    public void registerWithThread(Deque<Span> registerMe) {
        Deque<Span> currentSpanStack = currentSpanStackThreadLocal.get();

        // Do nothing if the passed-in stack is functionally identical to what we already have.
        if (!containsSameSpansInSameOrder(currentSpanStack, registerMe)) {
            // Not the same span stack. See if there was an old stale stack still around.
            if (currentSpanStack != null && currentSpanStack.size() > 0) {
                // Whoops, someone else is trying to register with this thread while it's already in the middle of handling spans.
                int originalSize = currentSpanStack.size();
                StringBuilder badTraceIds = new StringBuilder();

                // Complete and output all the spans, but they will all be marked "bad".
                while (!currentSpanStack.isEmpty()) {
                    Span span = currentSpanStack.pop();

                    if (badTraceIds.length() > 0)
                        badTraceIds.append(',');
                    badTraceIds.append(span.getTraceId());

                    completeAndLogSpan(span, true);
                }

                // Output an error message
                classLogger.error("DTRACE ERROR - We were asked to register a span stack with this thread (i.e. for systems that use threads asynchronously to perform work on " +
                                  "multiple requests at a time before any given request is completed) but there was already a non-empty span stack on this thread ({} total " +
                                  "spans when there should be zero). This probably means unregisterFromThread() was not called the last time this request's thread dropped it " +
                                  "to go work on a different request. Whenever a thread stops work on a request to go do something else when the request is not complete it " +
                                  "should unregisterFromThread() in a finally block or some other way to guarantee it doesn't leave an unfinished stack dangling. The bad " +
                                  "request span/sub-spans were logged but the reported total time spent on them will not be correct. dtracer_error=true, dirty_span_stack=true, " +
                                  "bad_child_span_ids={}",
                        originalSize,  badTraceIds.toString(), new Exception("from here")
                );
            }

            // At this point any errors have been handled and we can register the new stack. Make sure we register a copy so that changes to the original don't affect our stack.
            registerMe = (registerMe == null) ? null : new LinkedList<>(registerMe);
            currentSpanStackThreadLocal.set(registerMe);
        }

        // Make sure we fix the MDC to the passed-in info.
        Span newStackLatestSpan = (registerMe == null || registerMe.isEmpty()) ? null : registerMe.peek();
        if (newStackLatestSpan == null)
            unconfigureMDC();
        else
            configureMDC(newStackLatestSpan);
    }

}
