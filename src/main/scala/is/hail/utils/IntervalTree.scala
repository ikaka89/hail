package is.hail.utils

import is.hail.check._
import org.json4s.JValue
import org.json4s.JsonAST.JObject

import scala.collection.mutable
import scala.math.Ordering.Implicits._

// interval inclusive of start, exclusive of end: [start, end)
case class Interval[T](start: T, end: T)(implicit ev: Ordering[T]) extends Ordered[Interval[T]] {

  import ev._

  require(start <= end)

  def contains(position: T): Boolean = position >= start && position < end

  def overlaps(other: Interval[T]): Boolean = this.contains(other.start) || other.contains(this.start)

  def isEmpty: Boolean = start == end

  def compare(that: Interval[T]): Int = {
    var c = ev.compare(start, that.start)
    if (c != 0)
      return c

    ev.compare(end, that.end)
  }

  def toJSON(f: (T) => JValue): JValue = JObject("start" -> f(start), "end" -> f(end))
}

object Interval {
  def gen[T: Ordering](tgen: Gen[T]): Gen[Interval[T]] =
    Gen.zip(tgen, tgen)
      .map { case (x, y) =>
        if (x < y)
          Interval(x, y)
        else
          Interval(y, x)
      }
}

case class IntervalTree[T: Ordering](root: Option[IntervalTreeNode[T]]) extends Traversable[Interval[T]] with Serializable {
  def contains(position: T): Boolean = root.exists(_.contains(position))

  def overlaps(interval: Interval[T]): Boolean = root.exists(_.overlaps(interval))

  def query(position: T): Set[Interval[T]] = {
    val b = Set.newBuilder[Interval[T]]
    root.foreach(_.query(b, position))
    b.result()
  }

  def foreach[U](f: (Interval[T]) => U) =
    root.foreach(_.foreach(f))
}

object IntervalTree {
  def apply[T: Ordering](intervals: Array[Interval[T]], prune: Boolean = false): IntervalTree[T] = {
    val sorted = if (prune && intervals.nonEmpty) {
      val unpruned = intervals.sorted
      val ab = mutable.ArrayBuilder.make[Interval[T]]
      var tmp = unpruned.head
      var i = 1
      var pruned = 0
      while (i < unpruned.length) {
        val interval = unpruned(i)
        if (interval.start <= tmp.end) {
          val max = if (interval.end > tmp.end)
            interval.end
          else
            tmp.end
          tmp = Interval(tmp.start, max)
          pruned += 1
        } else {
          ab += tmp
          tmp = interval
        }

        i += 1
      }
      ab += tmp

      info(s"pruned $pruned redundant intervals")

      ab.result()
    } else intervals.sorted

    new IntervalTree[T](fromSorted(sorted, 0, sorted.length))
  }

  def fromSorted[T: Ordering](intervals: Array[Interval[T]], start: Int, end: Int): Option[IntervalTreeNode[T]] = {
    if (start >= end)
      None
    else {
      val mid = (start + end) / 2
      val i = intervals(mid)
      val lft = fromSorted(intervals, start, mid)
      val rt = fromSorted(intervals, mid + 1, end)
      Some(IntervalTreeNode(i, lft, rt, {
        val max1 = lft.map(_.maximum.max(i.end)).getOrElse(i.end)
        rt.map(_.maximum.max(max1)).getOrElse(max1)
      }))
    }
  }

  def gen[T: Ordering](tgen: Gen[T]): Gen[IntervalTree[T]] = {
    Gen.buildableOf[Array, Interval[T]](Interval.gen(tgen)) map {
      IntervalTree(_)
    }
  }
}

case class IntervalTreeNode[T: Ordering](i: Interval[T],
  left: Option[IntervalTreeNode[T]],
  right: Option[IntervalTreeNode[T]],
  maximum: T) extends Traversable[Interval[T]] {

  def contains(position: T): Boolean = {
    position <= maximum &&
      (left.exists(_.contains(position)) ||
        (position >= i.start &&
          (i.contains(position) ||
            right.exists(_.contains(position)))))
  }

  def overlaps(interval: Interval[T]): Boolean = {
    interval.start <= maximum && (left.exists(_.overlaps(interval))) ||
      i.overlaps(interval) || (right.exists(_.overlaps(interval)))
  }

  def query(b: mutable.Builder[Interval[T], _], position: T) {
    if (position <= maximum) {
      left.foreach(_.query(b, position))
      if (position >= i.start) {
        right.foreach(_.query(b, position))
        if (i.contains(position))
          b += i
      }
    }
  }

  def foreach[U](f: (Interval[T]) => U) {
    left.foreach(_.foreach(f))
    f(i)
    right.foreach(_.foreach(f))
  }
}
