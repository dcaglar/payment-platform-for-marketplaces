Structured Events Are the
Building Blocks of Observability
In this chapter, we examine the fundamental building block of observability: the
structured event. Observability is a measure of how well you can understand and
explain any state your system can get into, no matter how novel or bizarre. To do that,
you must be able to get answers to any question you can ask, without anticipating or
predicting a need to answer that question in advance. For that to be possible, several
technical prerequisites must first be met.
Throughout this book, we address the many technical prerequisites necessary for
observability. In this chapter, we start with the telemetry needed to understand and
explain any state your system may be in. Asking any question, about any combination
of telemetry details, requires the ability to arbitrarily slice and dice data along any
number of dimensions. It also requires that your telemetry be gathered in full resolu‐
tion, down to the lowest logical level of granularity that also retains the context in
which it was gathered. For observability, that means gathering telemetry at the per
service or per request level.
With old-fashioned-style metrics, you had to define custom metrics up front if you
wanted to ask a new question and gather new telemetry to answer it. In a metrics
world, the way to get answers to any possible question is to gather and store every
possible metric—which is simply impossible (and, even if it were possible, prohibi‐
tively expensive). Further, metrics do not retain the context of the event, simply an
aggregate measurement of what occurred at a particular time. With that aggregate
view, you could not ask new questions or look for new outliers in the existing data set.
With observability, you can ask new questions of your existing telemetry data at any
time, without needing to first predict what those questions might be. In an observable
51
system, you must be able to iteratively explore system aspects ranging from high-level
aggregate performance all the way down to the raw data used in individual requests.
The technical requirement making that possible starts with the data format needed
for observability: the arbitrarily wide structured event. Collecting these events is not
optional. It is not an implementation detail. It is a requirement that makes any level of
analysis possible within that wide-ranging view.
Debugging with Structured Events
First, let’s start by defining an event. For the purpose of understanding the impact to
your production services, an event is a record of everything that occurred while one
particular request interacted with your service.
To create that record, you start by initializing an empty map right at the beginning,
when the request first enters your service. During the lifetime of that request, any
interesting details about what occurred—unique IDs, variable values, headers, every
parameter passed by the request, the execution time, any calls made to remote
services, the execution time of those remote calls, or any other bit of context that may
later be valuable in debugging—get appended to that map. Then, when the request
is about to exit or error, that entire map is captured as a rich record of what just
happened. The data written to that map is organized and formatted, as key-value
pairs, so that it’s easily searchable: in other words, the data should be structured.
That’s a structured event.
As you debug problems in your service, you will compare structured events to one
another to find anomalies. When some events behave significantly differently from
others, you will then work to identify what those outliers have in common. Exploring
those outliers requires filtering and grouping by different dimensions—and combina‐
tions of dimensions—contained within those events that might be relevant to your
investigation.
The data that you feed into your observability system as structured events needs
to capture the right level of abstraction to help observers determine the state of
your applications, no matter how bizarre or novel. Information that is helpful for
investigators may have runtime information not specific to any given request (such as
container information or versioning information), as well as information about each
request that passes through the service (such as shopping cart ID, user ID, or session
token). Both types of data are useful for debugging.
The sort of data that is relevant to debugging a request will vary, but it helps to
think about that by comparing the type of data that would be useful when using a
conventional debugger. For example, in that setting, you might want to know the
values of variables during the execution of the request and to understand when
function calls are happening. With distributed services, those function calls may
52 | Chapter 5: Structured Events Are the Building Blocks of Observability
happen as multiple calls to remote services. In that setting, any data about the values
of variables during request execution can be thought of as the context of the request.
All of that data should be accessible for debugging and stored in your events. They
are arbitrarily wide events, because the debugging data you need may encompass a
significant number of fields. There should be no practical limit to the details you can
attach to your events. We’ll delve further into the types of information that should be
captured in structured events, but first let’s compare how capturing system state with
structured events differs from traditional debugging methods.
The Limitations of Metrics as a Building Block
First, let’s define metrics. Unfortunately, this term requires disambiguation because
often it is used as a generic synonym for “telemetry” (e.g., “all of our metrics are
up and to the right, boss!”). For clarity, when we refer to metrics, we mean the
scalar values collected to represent system state, with tags optionally appended for
grouping and searching those numbers. Metrics are the staple upon which traditional
monitoring of software systems has been built (refer to Chapter 1 for a more detailed
look at the origin of metrics in this sense).
However, the fundamental limitation of a metric is that it is a pre-aggregated meas‐
ure. The numerical values generated by a metric reflect an aggregated report of
system state over a predefined period of time. When that number is reported to a
monitoring system, that pre-aggregated measure now becomes the lowest possible
level of granularity for examining system state. That aggregation obscures many
possible problems. Further, a request into your service may be represented by having
a part in hundreds of metrics over the course of its execution. Those metrics are all
distinct measures—disconnected from one another—that lack the sort of connective
tissue and granularity necessary to reconstruct exactly which metrics belong to the
same request.
For example, a metric for page_load_time might examine the average time it took
for all active pages to load during the trailing five-second period. Another metric for
requests_per_second might examine the number of HTTP connections any given
service had open during the trailing one-second period. Metrics reflect system state,
as expressed by numerical values derived by measuring any given property over a
given period of time. The behavior of all requests that were active during that period
are aggregated into one numerical value. In this example, investigating what occurred
during the lifespan of any one particular request flowing through that system would
provide a trail of breadcrumbs that leads to both of these metrics. However, the level
of available granularity necessary to dig further is unavailable.
The Limitations of Metrics as a Building Block | 53
Delving into how metrics are stored in TSDBs is beyond the scope
of this chapter, but it is partially addressed in Chapter 16. For a
more in-depth look at how metrics and TSDBs are incompatible
with observability, we recommend Alex Vondrak’s 2021 blog post,
“How Time Series Databases Work—and Where They Don’t”.
In an event-based approach, a web server request could be instrumented to record
each parameter (or dimension) submitted with the request (for example, userid),
the intended subdomain (www, docs, support, shop, cdn, etc.), total duration time,
any dependent child requests, the various services involved (web, database, auth,
billing) to fulfill those child requests, the duration of each child request, and more.
Those events can then be arbitrarily sliced and diced across various dimensions,
across any window of time, to present any view relevant to an investigation.
In contrast to metrics, events are snapshots of what happened at a particular point
in time. One thousand discrete events may have occurred within that same trail‐
ing five-second period in the preceding example. If each event recorded its own
page_load_time, when aggregated along with the other 999 events that happened in
that same five-second period, you could still display the same average value as shown
by the metric. Additionally, with the granularity provided by events, an investigator
could also compute that same average over a one-second period, or subdivide queries
by fields like user ID or hostname to find correlations with page_load_time in ways
that simply aren’t possible when using the aggregated value provided by metrics.
An extreme counterargument to this analysis could be that, given enough metrics,
the granularity needed to see system state on the level of individual requests could
be achieved. Putting aside the wildly impractical nature of that approach, ignoring
that concurrency could never completely be accounted for, and that investigators
would still be required to stitch together which metrics were generated along the
path of a request’s execution, the fact remains that metrics-based monitoring systems
are simply not designed to scale to the degree necessary to capture those measures.
Regardless, many teams relying on metrics for debugging find themselves in an
escalating arms race of continuously adding more and more metrics in an attempt to
do just that.
As aggregate numerical representations of predefined relationships over predefined
periods of time, metrics serve as only one narrow view of one system property. Their
granularity is too large and their ability to present system state in alternate views is
too rigid to achieve observability. Metrics are too limiting to serve as the fundamental
building block of observability.
54 | Chapter 5: Structured Events Are the Building Blocks of Observability
The Limitations of Traditional Logs as a Building Block
As seen earlier, structured data is clearly defined and searchable. Unstructured data
isn’t organized in an easily searchable manner and is usually stored in its native for‐
mat. When it comes to debugging production software systems, the most prevalent
use of unstructured data is from a construct older than metrics. Let’s examine the use
of logs.
Before jumping into this section, we should note that it is modern logging practice
to use structured logs. But just in case you haven’t transitioned to using structured
logs, let’s start with a look at what unstructured logs are and how to make them more
useful.
Unstructured Logs
Log files are essentially large blobs of unstructured text, designed to be readable by
humans but difficult for machines to process. These files are documents generated by
applications and various underlying infrastructure systems that contain a record of
all notable events—as defined by a configuration file somewhere—that have occurred.
For decades, they have been an essential part of system debugging applications in any
environment. Logs typically contain tons of useful information: a description of the
event that happened, an associated timestamp, a severity-level type associated with
that event, and a variety of other relevant metadata (user ID, IP address, etc.).
Traditional logs are unstructured because they were designed to be human readable.
Unfortunately, for purposes of human readability, logs often separate the vivid details
of one event into multiple lines of text, like so:
6:01:00 accepted connection on port 80 from 10.0.0.3:63349
6:01:03 basic authentication accepted for user foo
6:01:15 processing request for /super/slow/server
6:01:18 request succeeded, sent response code 200
6:01:19 closed connection to 10.0.0.3:63349
While that sort of narrative structure can be helpful when first learning the intricacies
of a service in development, it generates huge volumes of noisy data that becomes
slow and clunky in production. In production, these chunks of narrative are often
interspersed throughout millions upon millions of other lines of text. Typically,
they’re useful in the course of debugging once a cause is already suspected and an
investigator is verifying their hypothesis by digging through logs for verification.
However, modern systems no longer run at an easily comprehensible human scale. In
traditional monolithic systems, human operators had a very small number of services
to manage. Logs were written to the local disk of the machines where applications
ran. In the modern era, logs are often streamed to a centralized aggregator, where
they’re dumped into very large storage backends.
The Limitations of Traditional Logs as a Building Block | 55
Searching through millions of lines of unstructured logs can be accomplished by
using some type of log file parser. Parsers split log data into chunks of information
and attempt to group them in meaningful ways. However, with unstructured data,
parsing gets complicated because different formatting rules (or no rules at all) exist
for different types of log files. Logging tools are full of different approaches to solving
this problem, with varying degrees of success, performance, and usability.
Structured Logs
The solution is to instead create structured log data designed for machine parsability.
From the preceding example, a structured version might instead look something like
this:
time="6:01:00" msg="accepted connection" port="80" authority="10.0.0.3:63349"
time="6:01:03" msg="basic authentication accepted" user="foo"
time="6:01:15" msg="processing request" path="/super/slow/server"
time="6:01:18" msg="sent response code" status="200"
time="6:01:19" msg="closed connection" authority="10.0.0.3:63349"
Many logs are only portions of events, regardless of whether those logs are structured.
When connecting observability approaches to logging, it helps to think of an event as
a unit of work within your systems. A structured event should contain information
about what it took for a service to perform a unit of work. A unit of work can be
seen as somewhat relative. For example, a unit of work could be downloading a
single file, parsing it, and extracting specific pieces of information. Yet other times, it
could mean processing an answer after extracting specific pieces of information from
dozens of files. In the context of services, a unit of work could be accepting an HTTP
request and doing everything necessary to return a response. Yet other times, one
HTTP request can generate many other events during its execution.
Ideally, a structured event should be scoped to contain everything about what it took
to perform that unit of work. It should record the input necessary to perform the
work, any attributes gathered—whether computed, resolved, or discovered—along
the way, the conditions of the service as it was performing the work, and details about
the result of the work performed.
It’s common to see anywhere from a few to a few dozen log lines or entries that,
when taken together, represent what could be considered one unit of work. So far,
the example we’ve been using does just that: one unit of work (the handling of one
connection) is represented by five separate log entries. Rather than being helpfully
grouped into one event, the messages are spread out into many messages. Sometimes,
a common field—like a request ID—might be present in each entry so that the
separate entries can be stitched together. But typically there won’t be.
56 | Chapter 5: Structured Events Are the Building Blocks of Observability
The log lines in our example could instead be rolled up into one singular event.
Doing so would make it look like this:
time="2019-08-22T11:56:11-07:00" level=info msg="Served HTTP request"
authority="10.0.0.3:63349" duration_ms=123 path="/super/slow/server" port=80
service_name="slowsvc" status=200 trace.trace_id=eafdf3123 user=foo
That representation can be formatted in different ways, including JavaScript Object
Notation (JSON). Most commonly, this type of event information could appear as a
JSON object, like so:
{
"authority":"10.0.0.3:63349",
"duration_ms":123,
"level":"info",
"msg":"Served HTTP request",
"path":"/super/slow/server",
"port":80,
"service_name":"slowsvc",
"status":200,
"time":"2019-08-22T11:57:03-07:00",
"trace.trace_id":"eafdf3123",
"user":"foo"
}
The goal of observability is to enable you to interrogate your event data in arbitrary
ways to understand the internal state of your systems. Any data used to do so must
be machine-parsable in order to facilitate that goal. Unstructured data is simply
unusable for that task. Log data can, however, be useful when redesigned to resemble
a structured event, the fundamental building block of observability.
Properties of Events That Are Useful in Debugging
Earlier in this chapter, we defined a structured event as a record of everything that
occurred while one particular request interacted with your service. When debugging
modern distributed systems, that is approximately the right scope for a unit of work.
To create a system where an observer can understand any state of the system—no
matter how novel or complex—an event must contain ample information that may be
relevant to an investigation.
The backend data store for an observable system must allow your events to be arbi‐
trarily wide. When using structured events to understand service behavior, remember
that the model is to initialize an empty blob and record anything that may be relevant
for later debugging. That can mean pre-populating the blob with data known as the
request enters your service: request parameters, environmental information, runtime
internals, host/container statistics, etc. As the request is executing, other valuable
information may be discovered: user ID, shopping cart ID, other remote services to
be called to render a result, or any various bits of information that may help you find
Properties of Events That Are Useful in Debugging | 57
and identify this request in the future. As the request exits your service, or returns
an error, there are several bits of data about how the unit of work was performed:
duration, response code, error messages, etc. It is common for events generated with
mature instrumentation to contain 300 to 400 dimensions per event.
Debugging novel problems typically means discovering previously unknown failure
modes (unknown-unknowns) by searching for events with outlying conditions and
finding patterns or correlating them. For example, if a spike of errors occurs, you
may need to slice across various event dimensions to find out what they all have in
common. To make those correlations, your observability solution will need the ability
to handle fields with high cardinality.
Cardinality refers to the number of unique elements in a set, as you
remember from Chapter 1. Some dimensions in your events will
have very low or very high cardinality. Any dimension containing
unique identifiers will have the highest possible cardinality. Refer
back to “The Role of Cardinality” on page 13 for more on this
topic.
An observability tool must be able to support high-cardinality queries to be useful
to an investigator. In modern systems, many of the dimensions that are most useful
for debugging novel problems have high cardinality. Investigation also often requires
stringing those high-cardinality dimensions together (i.e., high dimensionality) to
find deeply hidden problems. Debugging problems is often like trying to find a nee‐
dle in a haystack. High cardinality and high dimensionality are the capabilities that
enable you to find very fine-grained needles in deeply complex distributed system
haystacks.
For example, to get to the source of an issue, you may need to create a query that
finds “all Canadian users, running iOS11 version 11.0.4, using the French language
pack, who installed the app last Tuesday, running firmware version 1.4.101, who are
storing photos on shard3 in region us-west-1.” Every single one of those constraints is
a high-cardinality dimension.
As mentioned earlier, to enable that functionality, events must be arbitrarily wide:
fields within an event cannot be limited to known or predictable fields. Limiting
ingestible events to containing known fields may be an artificial constraint imposed
by the backend storage system used by a particular analysis tool. For example, some
backend data stores may require predefining data schemas. Predefining a schema
requires that users of the tool be able to predict, in advance, which dimensions may
need to be captured. Using schemas or other strict limitations on data types, or
shapes, are also orthogonal to the goals of observability (see Chapter 16).
58 | Chapter 5: Structured Events Are the Building Blocks of Observability
Conclusion
The fundamental building block of observability is the arbitrarily wide structured
event. Observability requires the ability to slice and dice data along any number
of dimensions, in order to answer any question when iteratively stepping your way
through a debugging process. Structured events must be arbitrarily wide because
they need to contain sufficient data to enable investigators to understand everything
that was happening when a system accomplished one unit of work. For distributed
services, that typically means scoping an event as the record of everything that
happened during the lifetime of one individual service request.
Metrics aggregate system state over a predefined period of time. They serve as only
one narrow view of one system property. Their granularity is too large, and their
ability to present system state in alternate views is too rigid, to stitch them together to
represent one unit of work for any particular service request. Metrics are too limiting
to serve as the fundamental building block of observability.
Unstructured logs are human readable but computationally difficult to use. Struc‐
tured logs are machine parsable and can be useful for the goals of observability,
presuming they are redesigned to resemble a structured event.
For now, we’ll simply concentrate on the data type necessary for telemetry. In the
next few chapters, we’ll more closely examine why analyzing high-cardinality and
high-dimensionality data is important. We will also further define the necessary capa‐
bilities for a tool to enable observability. Next, let’s start by looking at how structured
events can be stitched together to create traces.