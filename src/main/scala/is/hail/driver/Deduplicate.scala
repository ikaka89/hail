package is.hail.driver

import org.apache.spark.{Accumulable, AccumulableParam, Accumulator, AccumulatorParam}
import org.apache.spark.AccumulatorParam._
import is.hail.utils._
import is.hail.variant.Variant
import org.kohsuke.args4j.{Option => Args4jOption}

import scala.collection.mutable

object Deduplicate extends Command {

  class Options extends BaseOptions

  def newOptions = new Options

  def name = "deduplicate"

  def description = "Remove duplicate variants from this dataset"

  def supportsMultiallelic = true

  def requiresVDS = true

  object DuplicateAccumulator extends AccumulableParam[(Long, mutable.Set[Variant]), Variant] {
    override def addAccumulator(r: (Long, mutable.Set[Variant]), t: Variant): (Long, mutable.Set[Variant]) = {
      val (count, set) = r
      (count + 1, if (set.size < 10) set += t else set)
    }

    def addInPlace(t1: (Long, mutable.Set[Variant]), t2: (Long, mutable.Set[Variant])): (Long, mutable.Set[Variant]) = {
      val (count1, set1) = t1
      val (count2, set2) = t2
      val set = if (set1.size >= 10)
        set1
      else if (set2.size >= 10)
        set2
      else {
        (set1 ++= set2).take(10)
      }
      (count1 + count2, set)
    }

    def zero = (0L, mutable.Set.empty[Variant])

    def zero(initialValue: (Long, mutable.Set[Variant])): (Long, mutable.Set[Variant]) = zero
  }


  object DuplicateReport {

    var accumulator: Accumulable[(Long, mutable.Set[Variant]), Variant] = _

    def initialize() {
      accumulator = new Accumulable[(Long, mutable.Set[Variant]), Variant](DuplicateAccumulator.zero, DuplicateAccumulator)
    }

    def report() {

      Option(accumulator).foreach { accumulator =>
        val (count, variants) = accumulator.value
        if (count > 0) {
          info(s"filtered $count duplicate variants")
          info(
            s"""Select duplicated variants:
                |  ${ variants.toArray.sorted.mkString(", ") }""".stripMargin)
        } else
          info("no duplicate variants found")
      }
    }
  }

  def run(state: State, options: Options): State = {
    val vds = state.vds

    DuplicateReport.initialize()

    val acc = DuplicateReport.accumulator
    state.copy(vds = vds.copy(rdd = vds.rdd.mapPartitions({ it =>
      new SortedDistinctPairIterator(it, (v: Variant) => acc += v)
    }, preservesPartitioning = true).asOrderedRDD))
  }
}
