package org.broadinstitute.k3.methods

import org.broadinstitute.k3.variant._

import scala.collection.Map

// FIXME: need to account for all HomRef
object nGenotypeVectorPerSample {
  def apply(vds: VariantDataset): Map[Int, Vector[Int]] = {
    vds
      .mapValues(g =>
      if (g.isHomRef)
        Vector(1,0,0,0)
      else if (g.isHet)
        Vector(0,1,0,0)
      else if (g.isHomVar)
        Vector(0,0,1,0)
      else {
        assert(g.isNotCalled)
        Vector(0,0,0,1)
      })
      .foldBySample(Vector(0,0,0,0))((x, y) => Vector(x(0) + y(0), x(1) + y(1), x(2) + y(2), x(3) + y(3)))
  }
}

