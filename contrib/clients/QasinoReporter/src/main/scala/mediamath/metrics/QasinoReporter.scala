package mediamath.metrics

import com.codahale.metrics._
import com.fasterxml.jackson.databind.ObjectMapper
import dispatch._
import Defaults._
import java.util.concurrent.TimeUnit
import scala.collection
import scala.collection.JavaConversions._
import java.net.{Inet4Address, NetworkInterface}
import collection._
import java.util.{SortedMap => JavaSortedMap}
import scala.language.existentials
import scala.collection.immutable.ListMap

object QasinoReporter {

  val DEFAULT_PORT = 15597

	val registryNameSeparator = "_"
	val illegalCharRegex = new scala.util.matching.Regex("""[^A-Za-z0-9_]""")

	def sanitizeRegistryName(name: String): String = {
		// Remove any instances of the illegal characters from the name
		illegalCharRegex.replaceAllIn(name.toLowerCase, registryNameSeparator)
	}

	def sanitizeRegistry(registry: MetricRegistry): MetricRegistry = {
		// Return a new MetricRegistry with names sanitized for qasino
		val sanitizedRegistry = new MetricRegistry
		val metricMap = mapAsScalaMap(registry.getMetrics)
		for ((name, metric) <- metricMap) {
			sanitizedRegistry.register(sanitizeRegistryName(name), metric)
		}
		sanitizedRegistry
	}

	def getFirstNonLoopbackAddress: String = {
		val interfaces = NetworkInterface.getNetworkInterfaces
		//for (interface <- interfaces) {
		val nonLoopbackInterfaces = interfaces.filter(!_.isLoopback)
		val default = "unavailable"
		var address = default
		for (i <- nonLoopbackInterfaces if address == default) {
			for (addr <- i.getInetAddresses if addr.isInstanceOf[Inet4Address] && address == default) {
				address = addr.getHostAddress
			}
		}
		address
	}

  def forRegistry(registry: MetricRegistry): Builder.type = {
    Builder.registry = registry
    Builder
  }

  object Builder {
    var registry: MetricRegistry = new MetricRegistry
    var host: String = "localhost"
    var port: Int = DEFAULT_PORT
    var secure: Boolean = false
    var uri: String = "request"
    var db_op: String = "add_table_data"
    var name: String = "QasinoReporter"
    var db_persist: Boolean = false
    var groupings: Set[String] = SortedSet.empty
    var filter: MetricFilter = MetricFilter.ALL
    var rateUnit: TimeUnit = TimeUnit.SECONDS
    var durationUnit: TimeUnit = TimeUnit.MILLISECONDS

    def withPort(port: Int): this.type = {
      Builder.port = port
      this
    }

    def withHost(host: String): this.type = {
      Builder.host = host
      this
    }

    def secure(secure: Boolean = true): this.type = {
      Builder.secure = secure
      this
    }

    def withUri(uri: String): this.type = {
      Builder.uri = uri
      this
    }

    def withOp(db_op: String): this.type = {
      Builder.db_op = db_op
      this
    }

    def withName(name: String): this.type = {
      Builder.name = name
      this
    }

    def withPersist(db_persist: Boolean = true): this.type = {
      Builder.db_persist = db_persist
      this
    }

    def withGroupings(groupings: Set[String]): this.type = {
      Builder.groupings = groupings
      this
    }

    def withFilter(filter: MetricFilter): this.type = {
      Builder.filter = filter
      this
    }

    def convertRatesTo(rateUnit: TimeUnit): this.type = {
      Builder.rateUnit = rateUnit
      this
    }

    def convertDurationsTo(durationUnit: TimeUnit): this.type = {
      Builder.durationUnit = durationUnit
      this
    }

    def build(): QasinoReporter = {
      Builder.registry = QasinoReporter.sanitizeRegistry(Builder.registry)
      if (registryHasCollisions) {
        throw new IllegalArgumentException(
          "Found a collision within registry names after sanitation"
        )
      }
      if (hasIllegalColumnNames) {
        throw new IllegalArgumentException(
          "Found a column beginning with a non-alpha character"
        )
      }
      new QasinoReporter
    }
  }

  private def registryHasCollisions: Boolean = {
    // Check whether we have any name collisions after some sanitizing
    val namesSet = mutable.Set[String]()
    val registryNames = Builder.registry.getNames
    for (name <- asScalaSet(registryNames)) {
      val sanitizedName = QasinoReporter.sanitizeRegistryName(name)
      namesSet.add(sanitizedName)
    }
    namesSet.size < registryNames.size()
  }

  private def hasIllegalColumnNames: Boolean = {
    var hasIllegalColName = false
    for (name <- Builder.registry.getNames if !hasIllegalColName) {
      val thisGrouping: Option[String] =
        Builder.groupings.toSeq.sortBy(_.length).reverse.find(s => name.startsWith(s + "_"))
      val suffix: String = if (thisGrouping.isDefined) {
        name.drop(thisGrouping.get.length + 1)
      }
      else name
      hasIllegalColName = suffix.matches("^[^A-Za-z].*")
    }
    hasIllegalColName
  }
}


class QasinoReporter extends
		ScheduledReporter(
      QasinoReporter.Builder.registry,
      QasinoReporter.Builder.name,
      QasinoReporter.Builder.filter,
      QasinoReporter.Builder.rateUnit,
      QasinoReporter.Builder.durationUnit) {
	val registry: MetricRegistry = QasinoReporter.Builder.registry
	val host: String = QasinoReporter.Builder.host
	val port: Int = QasinoReporter.Builder.port
	val secure: Boolean = QasinoReporter.Builder.secure
	val uri: String = QasinoReporter.Builder.uri
	val db_op: String = QasinoReporter.Builder.db_op
	val db_persist: Boolean = QasinoReporter.Builder.db_persist
	val name: String = QasinoReporter.Builder.name
	val groupings: Set[String] = QasinoReporter.Builder.groupings
	val filter: MetricFilter = QasinoReporter.Builder.filter
	val rateUnit: TimeUnit = QasinoReporter.Builder.rateUnit
	val durationUnit: TimeUnit = QasinoReporter.Builder.durationUnit

  // Set up Dispatch HTTP client
	private val dispatchHost = if (secure) dispatch.host(host, port).secure else dispatch.host(host, port)
	private val dispatchRequest = (dispatchHost / uri).POST <<? Map("op" -> db_op)

	// JSON mapper singleton
	private val mapper = new ObjectMapper()

	object QasinoRequestIdentifier extends scala.Enumeration {
		// Enumeration for all the JSON keys for qasino for safety
		type QasinoRequestIdentifier = Value
		val op, identity, tablename, table, column_names, column_types, rows, persist = Value
	}
	import QasinoRequestIdentifier._

	// Default map for JSON
	private[this] val db_persist_int = if (db_persist) 1 else 0
	private val defaultDataJson = mutable.Map[String, Any](
		op.toString -> db_op,
		identity.toString -> QasinoReporter.getFirstNonLoopbackAddress,
		persist.toString -> db_persist_int,
		table.toString -> mutable.Map[String, Any](
			tablename.toString -> Unit,
			column_names.toString-> Unit,
			column_types.toString -> Unit
		)
	)

	// Shorthand for a two dimensional map of any type
	type TwoDMap[K1, K2, Val] = ListMap[K1, ListMap[K2, Val]]

	def getColumnNames(metric: Metric, prefixWithSeparator: String = ""): Seq[String] = metric match {
		// Get the qasino column names for any metric type
		case gauge: Gauge[_] =>
			Seq(
        "value"
      ) map {prefixWithSeparator + _}
		case _: Counter =>
			Seq(
        "count"
      ) map {prefixWithSeparator + _}
		case _: Histogram =>
			Seq(
        "count",
        "max",
        "mean",
        "mid",
        "stddev",
        "p50",
        "p75",
        "p95",
        "p98",
        "p99",
        "p999"
      ) map {prefixWithSeparator + _}
		case _: Meter =>
			Seq(
        "count",
        "mean_rate",
        "m1_rate",
        "m5_rate",
        "m15_rate",
        "rate_unit"
      ) map {prefixWithSeparator + _}
		case _: Timer =>
			Seq(
        "count",
        "max",
        "mean",
        "min",
        "stddev",
        "p50",
        "p75",
        "p95",
        "p98",
        "p99",
        "p999",
        "mean_rate",
        "m1_rate",
        "m5_rate",
        "m15_rate",
        "rate_unit",
        "duration_unit"
      ) map {prefixWithSeparator + _}
		case _ => Seq.empty[String]
	}

	def getGroupedColumnNames(groupedMetrics: TwoDMap[String, String, Metric], prefix: String): Seq[String] = {
		var groupColumnNames = Seq.empty[String]
		val metricMap = groupedMetrics.getOrElse(prefix, Map.empty[String, Metric])
		for ((suffix, metric) <- metricMap) {
			val thisMetricColumnNames = getColumnNames(metric, suffix + "_")
			groupColumnNames = groupColumnNames ++ thisMetricColumnNames
		}
		groupColumnNames
	}

	def getColumnTypes(metric: Metric, prefix: String = ""): Seq[String] = metric match {
		// Get the qasino column types for any metric type
		case _: Gauge[_] => Seq(
      "string" // value
    )
		case _: Counter => Seq(
      "int" // count
    )
		case _: Histogram => Seq(
      "int", // count
      "int", // max
      "int", // mean
      "int", // min
      "int", // stddev
      "int", // p50
      "int", // p75
      "int", // p95
      "int", // p98
      "int", // p99
      "int"  // p999
    )
		case _: Meter => Seq(
      "int", // count
      "int", // mean_rate
      "int", // m1_rate
      "int", // m5_rate
      "int", // m15_rate
      "string" // rate_unit
    )
		case _: Timer => Seq(
      "int", // count
      "int", // max
      "int", // mean
      "int", // min
      "int", // stddev
      "int", // 50p
      "int", // 75p
      "int", // 95p
      "int", // 98p
      "int", // 99p
      "int", // 999p
      "int", // mean_rate
      "int", // m1_rate
      "int", // m5_rate
      "int", // m15_rate
      "string", // rate_unit
      "string"  // duration_unit
    )
		case _ => Seq.empty[String]
	}

	def getGroupedColumnTypes(groupedMetrics: TwoDMap[String, String, Metric], prefix: String): Seq[String] = {
		var groupColumnTypes = Seq.empty[String]
		val metricMap = groupedMetrics.getOrElse(prefix, Map.empty[String, Metric])
		for ((_, metric) <- metricMap) {
			val thisMetricColumnTypes = getColumnTypes(metric)
			groupColumnTypes = groupColumnTypes ++ thisMetricColumnTypes
		}
		groupColumnTypes
	}

	def getColumnValues(metric: Metric) = metric match {
		// Get the qasino column values for any metric type
		case gauge: Gauge[_] => Array(gauge.getValue.toString)
		case counter: Counter => Array(counter.getCount)
		case histogram: Histogram =>
			val snap = histogram.getSnapshot
			Array(
        histogram.getCount,
        snap.getMax,
        snap.getMean,
        snap.getMin,
        snap.getStdDev,
        snap.getMedian,
        snap.get75thPercentile(),
        snap.get95thPercentile(),
        snap.get98thPercentile(),
        snap.get99thPercentile(),
        snap.get999thPercentile()
      )
		case meter: Meter =>
			Array(
        meter.getCount,
        rateUnit,
        meter.getOneMinuteRate,
        meter.getFiveMinuteRate,
        meter.getFifteenMinuteRate,
        meter.getMeanRate
      )
		case timer: Timer =>
      val snap = timer.getSnapshot
			Array(
        timer.getCount,
        snap.getMax,
        snap.getMean,
        snap.getMin,
        snap.getStdDev,
        snap.getMedian,
        snap.get75thPercentile,
        snap.get95thPercentile,
        snap.get98thPercentile,
        snap.get99thPercentile,
        snap.get999thPercentile,
        timer.getMeanRate,
        timer.getOneMinuteRate,
        timer.getFiveMinuteRate,
        timer.getFifteenMinuteRate
      )
	}

	def getGroupedColumnValues(groupedMetrics: TwoDMap[String, String, Metric], prefix: String):
	Array[Any] = {
		var groupColumnValues = Array.empty[Any]
		val metricMap = groupedMetrics.getOrElse(prefix, Map.empty[String, Metric])
		for ((_, metric) <- metricMap) {
			val thisMetricColumnValues = getColumnValues(metric)
			groupColumnValues = groupColumnValues ++ thisMetricColumnValues
		}
		groupColumnValues
	}

	def getJsonForMetric(metric: Metric, name: String): String = {
		// Get the qasino json data for any metric type
		var postDataMap = defaultDataJson
		val col_names = seqAsJavaList(getColumnNames(metric))
		val col_types = seqAsJavaList(getColumnTypes(metric))
		val r = java.util.Arrays.asList(seqAsJavaList(getColumnValues(metric)))
		val tableMap = mutable.Map[String, Any](
			tablename.toString -> name,
			column_names.toString -> col_names,
			column_types.toString -> col_types,
			rows.toString -> r
		)
		postDataMap = postDataMap + (table.toString -> mapAsJavaMap(tableMap))
		mapper.writeValueAsString(mapAsJavaMap(postDataMap))
	}

	def getGroupedJson(groupedMetrics: TwoDMap[String, String, Metric], prefix: String): String = {
		// Get the qasino json data for any grouped metric type
		var postDataMap = defaultDataJson
		val col_names = seqAsJavaList(getGroupedColumnNames(groupedMetrics, prefix))
		val col_types =  seqAsJavaList(getGroupedColumnTypes(groupedMetrics, prefix))
		val r = java.util.Arrays.asList(seqAsJavaList(getGroupedColumnValues(groupedMetrics, prefix)))
		val tableMap = mutable.Map[String, Any](
			tablename.toString -> prefix,
			column_names.toString -> col_names,
			column_types.toString -> col_types,
			rows.toString -> r
		)
		postDataMap = postDataMap + (table.toString -> mapAsJavaMap(tableMap))
		mapper.writeValueAsString(mapAsJavaMap(postDataMap))
	}

	def groupMetrics(metrics: Map[String, Metric]): TwoDMap[String, String, Metric] = {
		var groupedMetrics: TwoDMap[String, String, Metric] = ListMap.empty
		val emptryString = ""
		for ((name, metric) <- metrics) {
			// Match groups going by longest group name to shortest
			val thisGrouping: Option[String] =
				groupings.toSeq.sortBy(_.length).reverse.find(s => name.startsWith(s + "_"))
			val suffix: String = if (thisGrouping.isDefined) {
				// Add one to the length for the separator
				name.drop(thisGrouping.get.length + 1)
			}
			else name
			var subgroupedMetrics: ListMap[String, Metric] = groupedMetrics.getOrElse(thisGrouping.getOrElse(emptryString), ListMap.empty)
			subgroupedMetrics = subgroupedMetrics + (suffix -> metric)
			groupedMetrics = groupedMetrics + (thisGrouping.getOrElse(emptryString) -> subgroupedMetrics)
		}
		groupedMetrics
	}

	def getJsonForMetrics(nameToMetric: ListMap[String, Metric]): Seq[String] = {
		var jsonForMetrics = Seq.empty[String]
		val groupedMetrics = groupMetrics(mapAsScalaMap(nameToMetric))
		for ((prefix, metricMap) <- groupedMetrics) {
			if (prefix.isEmpty) {
				// No prefix, process this metric by itself
				for ((name, metric) <- metricMap) {
					jsonForMetrics = jsonForMetrics :+ getJsonForMetric(metric, name)
				}
			}
			else {
				// This metric is part of a group, all of whom should be reported together
				jsonForMetrics = jsonForMetrics :+ getGroupedJson(groupedMetrics, prefix)
			}
		}
		jsonForMetrics
	}

	def reportToQasino(nameToMetric: ListMap[String, Metric]) {
		for (jsonStr <- getJsonForMetrics(nameToMetric)) {
			val postWithParams = dispatchRequest << jsonStr
			dispatch.Http(postWithParams OK as.String)
		}
	}

	def combineMetricsToMap(
		 gauges: JavaSortedMap[String, Gauge[_]] = registry.getGauges,
		 counters: JavaSortedMap[String, Counter] = registry.getCounters,
		 histograms: JavaSortedMap[String, Histogram] = registry.getHistograms,
		 meters: JavaSortedMap[String, Meter] = registry.getMeters,
		 timers: JavaSortedMap[String, Timer] = registry.getTimers): ListMap[String, Metric] = {
		ListMap(gauges.toSeq: _*) ++
		ListMap(counters.toSeq: _*) ++
		ListMap(histograms.toSeq: _*) ++
		ListMap(meters.toSeq: _*) ++
		ListMap(timers.toSeq: _*)
	}

  override def report (
			gauges: JavaSortedMap[String, Gauge[_]],
			counters: JavaSortedMap[String, Counter],
			histograms: JavaSortedMap[String, Histogram],
			meters: JavaSortedMap[String, Meter],
			timers: JavaSortedMap[String, Timer]) {

		reportToQasino(combineMetricsToMap(gauges, counters, histograms, meters, timers))
	}

  def shutdown(): Unit = {
    stop() // Stop the timer
    report() // Flush out the remaining data
  }
}