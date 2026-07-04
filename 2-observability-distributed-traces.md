Stitching Events into Traces
In the previous chapter, we explored why events are the fundamental building blocks
of an observable system. This chapter examines how you can stitch events together
into a trace. Within the last decade, distributed tracing has become an indispensable
troubleshooting tool for software engineering teams.
Distributed traces are simply an interrelated series of events. Distributed tracing sys‐
tems provide packaged libraries that “automagically” create and manage the work of
tracking those relationships. The concepts used to create and track the relationships
between discrete events can be applied far beyond traditional tracing use cases. To
further explore what’s possible with observable systems, we must first explore the
inner workings of tracing systems.
In this chapter, we demystify distributed tracing by examining its core components
and why they are so useful for observable systems. We explain the components of
a trace and use code examples to illustrate the steps necessary to assemble a trace
by hand and how those components work. We present examples of adding relevant
data to a trace event (or span) and why you may want that data. Finally, after
showing you how a trace is assembled manually, we’ll apply those same techniques to
nontraditional tracing use cases (like stitching together log events) that are possible
with observable systems.
Distributed Tracing and Why It Matters Now
Tracing is a fundamental software debugging technique wherein various bits of
information are logged throughout a program’s execution for the purpose of diagnos‐
ing problems. Since the very first day that two computers were linked together to
exchange information, software engineers have been discovering the gremlins lurking
within our programs and protocols. Those issues in the seams persist despite our
61
best efforts, and in an age when distributed systems are the norm, the debugging
techniques we use must adapt to meet more complex needs.
Distributed tracing is a method of tracking the progression of a single request—called
a trace—as it is handled by various services that make up an application. Tracing in
this sense is “distributed” because in order to fulfill its functions, a singular request
must often traverse process, machine, and network boundaries. The popularity of
microservice architectures has led to a sharp increase in debugging techniques that
pinpoint where failures occur along that route and what might be contributing to
poor performance. But anytime a request traverses boundaries—such as from on-
premises to cloud infrastructure, or from infrastructure you control to SaaS services
you don’t, and back again—distributed tracing can be incredibly useful to diagnose
problems, optimize code, and build more reliable services.
The rise in popularity of distributed tracing also means that several approaches and
competing standards for accomplishing that task have emerged. Distributed tracing
first gained mainstream traction after Google’s publication of the Dapper paper by
Ben Sigelman et al. in 2010. Two notable open source tracing projects emerged
shortly after: Twitter’s Zipkin in 2012 and Uber’s Jaeger in 2017, in addition to several
commercially available solutions such as Honeycomb or Lightstep.
Despite the implementation differences in these tracing projects, the core method‐
ology and the value they provide are the same. As explored in Part I, modern
distributed systems have a tendency to scale into a tangled knot of dependencies.
Distributed tracing is valuable because it clearly shows the relationships among
various services and components in a distributed system.
Traces help you understand system interdependencies. Those interdependencies can
obscure problems and make them particularly difficult to debug unless the relation‐
ships between them are clearly understood. For example, if a downstream database
service experiences performance bottlenecks, that latency can cumulatively stack up.
By the time that latency is detected three or four layers upstream, identifying which
component of the system is the root of the problem can be incredibly difficult
because now that same latency is being seen in dozens of other services.
In an observable system, a trace is simply a series of interconnected events. To
understand how traces relate to the fundamental building blocks of observability, let’s
start by looking at how traces are assembled.
62 | Chapter 6: Stitching Events into Traces
The Components of Tracing
To understand the mechanics of how tracing works in practice, we’ll use an example
to illustrate the various components needed to collect the data necessary for a trace.
First, we’ll consider the outcome we want from a trace: to clearly see relationships
among various services. Then we’ll look at how we might modify our existing code to
get that outcome.
To quickly understand where bottlenecks may be occurring, it’s useful to have
waterfall-style visualizations of a trace, as shown in Figure 6-1. Each stage of a request
is displayed as an individual chunk in relation to the start time and duration of a
request being debugged.
Waterfall visualizations show how an initial value is affected by a
series of intermediate values, leading to a final cumulative value.
Figure 6-1. This waterfall-style trace visualization displays four trace spans during one
request
Each chunk of this waterfall is called a trace span, or span for short. Within any given
trace, spans are either the root span—the top-level span in that trace—or are nested
within the root span. Spans nested within the root span may also have nested spans of
their own. That relationship is sometimes referred to as parent-child. For example, in
Figure 6-2, if Service A calls Service B, which calls Service C, then for that trace, Span
A is the parent of Span B, which is in turn the parent of Span C.
The Components of Tracing | 63
Figure 6-2. A trace that has two parent spans. Span A is the root span and is also the
parent of Span B. Span B is a child of Span A and also the parent of Span C. Span C is a
child of Span B and has no child spans of its own.
Note that a service might be called and appear multiple times within a trace as
separate spans, such as in the case of circular dependencies or intense calculations
broken into parallel functions within the same service hop. In practice, requests often
traverse messy and unpredictable paths through a distributed system. To construct
the view we want for any path taken, no matter how complex, we need five pieces of
data for each component:
Trace ID
We need a unique identifier for the trace we’re about to create so that we can map
it back to a particular request. This ID is created by the root span and propagated
throughout each step taken to fulfill the request.
Span ID
We also need a unique identifier for each individual span created. Spans contain
information captured while a unit of work occurred during a single trace. The
unique ID allows us to refer to this span whenever we need it.
Parent ID
This field is used to properly define nesting relationships throughout the life of
the trace. A Parent ID is absent in the root span (that’s how we know it’s the
root).
Timestamp
Each span must indicate when its work began.
Duration
Each span must also record how long that work took to finish.
Those fields are absolutely required in order to assemble the structure of a trace.
However, you will likely find a few other fields helpful when identifying these spans
or how they relate to your system. Any additional data added to a span is essentially a
series of tags.
64 | Chapter 6: Stitching Events into Traces
These are some examples:
Service Name
For investigative purposes, you’ll want to indicate the name of the service where
this work occurred.
Span Name
To understand the relevancy of each step, it’s helpful to give each span a name
that identifies or differentiates the work that was being done—e.g., names could
be intense_computation1 and intense_computation2 if they represent different
work streams within the same service or network hop.
With that data, we should be able to construct the type of waterfall visualization we
want for any request in order to quickly diagnose any issues. Next, let’s look at how
we would instrument our code to generate that data.
Instrumenting a Trace the Hard Way
To understand how the core components of a trace come together, we’ll create a
manual example of an overly simple tracing system. In any distributed tracing system,
quite a bit more information is being added to traces to make the data more usable.
For example, you may wish to enrich a trace with additional metadata prior to
sending it to a backend data store (see Chapter 16).
If you wish to make an apple pie from scratch, you must first invent the universe.
—Carl Sagan
For illustration purposes, we’ll presume that a backend for collection of this data
already exists and will focus on just the client-side instrumentation necessary for
tracing. We’ll also presume that we can send data to that system via HTTP.
Let’s say that we have a simple web endpoint. For quick illustrative purposes, we will
create this example with Go as our language. When we issue a GET request, it makes
calls to two other services to retrieve data based on the payload of the request (such
as whether the user is authorized to access the given endpoint) and then it returns the
results:
func rootHandler(r *http.Request, w http.ResponseWriter) {
authorized := callAuthService(r)
name := callNameService(r)
if authorized {
} else {
w.Write([]byte(fmt.Sprintf(`{"message": "Waddup %s"}`, name)))
w.Write([]byte(`{"message": "Not cool dawg"}`))
}
}
Instrumenting a Trace the Hard Way | 65
The main purpose of distributed tracing is to follow a request as it traverses multiple
services. In this example, because this request makes calls to two other services, we
would expect to see a minimum of three spans when making this request:
• The originating request to rootHandler•
• The call to our authorization service (to authenticate the request)
•
• The call to our name service (to get the user’s name)
•
First, let’s generate a unique trace ID so we can group any subsequent spans back
to the originating request. We’ll use UUIDs to avoid any data duplication issues and
store the attributes and data for this span in a map (we could then later serialize that
data as JSON to send it to our data backend). We’ll also generate a span ID that can be
used as an identifier for relating different spans in the same trace together:
func rootHandler(...) {
traceData := make(map[string]interface{})
traceData["trace_id"] = uuid.String()
traceData["span_id"] = uuid.String()
// ... main work of request ...
}
Now that we have IDs that can be used to string our requests together, we’ll also
want to know when this span started and how long it took to execute. We do that
by capturing a timestamp, both when the request starts and when it ends. Noting the
difference between those two timestamps, we will calculate duration in milliseconds:
func rootHandler(...) {
// ... trace id setup from above ...
startTime := time.Now()
traceData["timestamp"] = startTime.Unix()
// ... main work of request ...
traceData["duration_ms"] = time.Now().Sub(startTime)
}
Finally, we’ll add two descriptive fields: service_name indicates which service the
work occurred in, and span name indicates the type of work we did. Additionally, we’ll
set up this portion of our code to send all of this data to our tracing backend via a
remote procedure call (RPC) once it’s all complete:
func loginHandler(...) {
// ... trace id and duration setup from above ...
traceData["name"] = "/oauth2/login"
traceData["service_name"] = "authentication_svc"
66 | Chapter 6: Stitching Events into Traces
// ... main work of request ...
sendSpan(traceData)
}
We have the portions of data we need for this one singular trace span. However,
we don’t yet have a way to relay any of this trace data to the other services that
we’re calling as part of our request. At the very least, we need to know which span
this is within our trace, which parent this span belongs to, and that data should be
propagated throughout the life of the request.
The most common way that information is shared in distributed tracing systems
is by setting it in HTTP headers on outbound requests. In our example, we could
expand our helper functions callAuthService and callNameService to accept the
traceData map and use it to set special HTTP headers on their outbound requests.
You could call those headers anything you want, as long as the programs on the
receiving end understand those same names. Typically, HTTP headers follow a par‐
ticular standard, such as those of the World Wide Web Consortium (W3C) or B3. For
our example, we’ll use the B3 standard. We would need to send the following headers
(as in Figure 6-3) to ensure that child spans are able to build and send their spans
correctly:
X-B3-TraceId
Contains the trace ID for the entire trace (from the preceding example)
X-B3-ParentSpanId
Contains the current span ID, which will be set as the parent ID in the child’s
generated span
Now let’s ensure that those headers are sent in our outbound HTTP request:
func callAuthService(req *http.Request, traceData map[string]interface{}) {
aReq, _ = http.NewRequest("GET", "http://authz/check_user", nil)
aReq.Header.Set("X-B3-TraceId", traceData["trace.trace_id"])
aReq.Header.Set("X-B3-ParentSpanId", traceData["trace.span_id"])
// ... make the request ...
}
We would also make a similar change to our callNameService function. With that,
when each service is called, it can pull the information from these headers and add
them to trace_id and parent_id in their own generation of traceData. Each of
those services would also send their generated spans to the tracing backend. On the
backend, those traces are stitched together to create the waterfall-type visualization
we want to see.
Instrumenting a Trace the Hard Way | 67
Figure 6-3. Our example app would now propagate traceID and parentID to each child
span
Now that you’ve seen what goes into instrumenting and creating a useful trace view,
let’s see what else we might want to add to our spans to make them more useful for
debugging.
Adding Custom Fields into Trace Spans
Understanding parent-child relationships and execution duration is a good start. But
you may want to add other fields in addition to the necessary trace data to better
understand what’s happening in each span whose operation is typically buried deeply
within your distributed systems.
For example, in addition to storing the service name for ease of identification , it
might be useful to know the exact host on which the work was executed and whether
it was related to a particular user. Let’s modify our example to capture those details as
part of our trace span by adding them as key-value pairs:
hostname, _ := os.Hostname()
traceData["tags"] = make(map[string]interface{})
traceData["tags"]["hostname"] = hostname
traceData["tags"]["user_name"] = name
You could further extend this example to capture any other system information you
might find relevant for debugging such as the application’s build_id, instance_type,
information about your runtime, or any plethora of details like any of the examples in
Chapter 5. For now, we’ll keep it simple.
Putting this all together, our full example app that creates a trace from scratch would
look something like this (the code is repetitive and verbose for clarity):
func rootHandler(r *http.Request, w http.ResponseWriter) {
traceData := make(map[string]interface{})
traceData["tags"] = make(map[string]interface{})
68 | Chapter 6: Stitching Events into Traces
hostname, _ := os.Hostname()
traceData["tags"]["hostname"] = hostname
traceData["tags"]["user_name"] = name
startTime := time.Now()
traceData["timestamp"] = startTime.Unix()
traceData["trace_id"] = uuid.String()
traceData["span_id"] = uuid.String()
traceData["name"] = "/oauth2/login"
traceData["service_name"] = "authentication_svc"
func callAuthService(req *http.Request, traceData map[string]interface{}) {
aReq, _ = http.NewRequest("GET", "http://authz/check_user", nil)
aReq.Header.Set("X-B3-TraceId", traceData["trace.trace_id"])
aReq.Header.Set("X-B3-ParentSpanId", traceData["trace.span_id"])
// ... make the auth request ...
}
func callNameService(req *http.Request, traceData map[string]interface{}) {
nReq, _ = http.NewRequest("GET", "http://authz/check_user", nil)
nReq.Header.Set("X-B3-TraceId", traceData["trace.trace_id"])
nReq.Header.Set("X-B3-ParentSpanId", traceData["trace.span_id"])
// ... make the name request ...
}
authorized := callAuthService(r)
name := callNameService(r)
if authorized {
} else {
w.Write([]byte(fmt.Sprintf(`{"message": "Waddup %s"}`, name)))
w.Write([]byte(`{"message": "Not cool dawg"}`))
}
traceData["duration_ms"] = time.Now().Sub(startTime)
sendSpan(traceData)
}
The code examples used in this section are a bit contrived to illustrate how these
concepts come together in practice. The good news is that you would typically not
have to generate all of this code yourself. Distributed tracing systems commonly have
their own supporting libraries to do most of this boilerplate setup work.
These shared libraries are typically unique to the particular needs of the tracing
solution you wish to use. Unfortunately, vendor-specific solutions do not work well
with other tracing solutions, meaning that you have to re-instrument your code if
you want to try a different solution. In the next chapter, we’ll look at the open source
Adding Custom Fields into Trace Spans | 69
OpenTelemetry project and how it enables you to instrument only once and use a
wide variety of solutions.
Now that you have a complete view of what goes into instrumenting and creating
useful trace views, let’s apply that to what you learned in the preceding chapter to
understand why tracing is such a key element in observable systems.
Stitching Events into Traces
In Chapter 5, we examined the use of structured events as the building blocks of
an observable system. We defined an event as a record of everything that occurred
while one particular request interacted with your service. During the lifetime of that
request, any interesting details about what occurred in order to return a result should
be appended to the event.
In the code examples, our functions were incredibly simple. In a real application, each
service call made throughout the execution of a single request would have its own
interesting details about what occurred: variable values, parameters passed, results
returned, associated context, etc. Each event would capture those details along with
the traceData that later allows you to see and debug the relationships among various
services and components in your distributed systems.
In our examples—as well as in distributed tracing systems in general—the instrumen‐
tation we used was added at the remote-service-call level. However, in an observable
system, you could use the same approach to tie together any number of correlated
events from different sources. For example, you could take a first step toward observ‐
ability by migrating your current single-line logging solution toward a more cohesive
view by applying these same trace concepts.
To do that, you could migrate from generating unstructured multiline logs to gener‐
ating structured logs (see Chapter 5). Then you could add the same required fields
for traceData to your structured logs. Having done so, you could generate the same
waterfall-style view from your log data. We wouldn’t recommend that as a long-term
approach, but it can be useful, especially when first getting started (see Chapter 10 for
more tips).
A more common scenario for a nontraditional use of tracing is to do a chunk of
work that is not distributed in nature, but that you want to split into its own span
for a variety of reasons. For example, perhaps you find that your application is
bottlenecked by JSON unmarshaling (or some other CPU-intensive operation) and
you need to identify when that causes a problem.
One approach is to wrap these “hot blocks” of code into their own separate spans
to get an even more detailed waterfall view (see Chapter 7 for more examples). That
approach can be used to create traces in non-distributed (monolithic) or non-service-
70 | Chapter 6: Stitching Events into Traces
oriented programs. For example, you could create a span for every chunk of work
in a batch job (e.g., every object uploaded to Amazon Simple Storage Service, or S3)
or for each distinct phase of an AWS Lambda-based pipeline. Indeed, AWS software
development kits (SDKs) do this by default; see the AWS Developer Guide for more
details.
In an observable system, any set of events can be stitched into traces. Tracing doesn’t
have to be limited to service-to-service calls. So far, we’ve focused only on gathering
the data in these events and sending them to our observability backend. In later
chapters, we’ll look at the analysis methods that allow you to arbitrarily slice and dice
that data to find patterns along any dimension you choose.
Conclusion
Events are the building blocks of observability, and traces are simply an interrelated
series of events. The concepts used to stitch together spans into a cohesive trace
are useful in the setting of service-to-service communication. In observable systems,
those same concepts can also be applied beyond making RPCs to any discrete events
in your systems that are interrelated (like individual file uploads all created from the
same batch job).
In this chapter, we instrumented a trace the hard way by coding each necessary step
by hand. A more practical way to get started with tracing is to use an instrumentation
framework. In the next chapter, we’ll look at the open source and vendor-neutral
OpenTelemetry project as well as how and why you would use it to instrument your
production applications.