/* Copyright (C) 2008-2010 Univ of Massachusetts Amherst, Computer Science Dept
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   This software is provided under the terms of the Eclipse Public License 1.0
   as published by http://www.opensource.org.  For further information,
   see the file `LICENSE.txt' included with this distribution. */

package cc.factorie
import scala.collection.mutable.HashSet
import cc.factorie.la._

// A collection of abstract Variables (and a generic Template) for generative models (directed Bayesian networks, 
// as opposed to undirected in which there is not a DAG-shaped generative storyline).

/** A value that has been generated by a probability distribution parameterized by some parent variables.
    May or may not be mutable. */
trait GeneratedValue extends Variable {
  /** The list of random variables that control the generation of this value. */
  def parents: Seq[Parameter]
  /** Sometimes pointers to parents are kept in a ParameterRef variable; 
      if so, return them here so that we can track diffs to them; 
      if not, return Nil or null entries in sequence with ordering matching 'parents'. */
  def parentRefs: Seq[AbstractParameterRef] = Nil
  /** The probability of the current value given its parents. */
  def pr:Double
  /** The log-probability of the current value given its parents. */
  def logpr:Double = math.log(pr)
}

/** A GeneratedValue that is mutable and whose value may be changed by sampling. */
trait GeneratedVariable extends GeneratedValue {
  /** Sample a new value for this variable given only its parents */
  def sample(implicit d:DiffList): Unit // TODO Consider renaming sampleFromParents to make clear that it doesn't account for children or others
  /** Sample a new value for this variable given only the specified parents, 
      ignoring its current registered parents. */
  def sampleFrom(parents:Seq[Variable])(implicit d:DiffList): Unit
}


trait RealGenerating {
  def sampleDouble: Double
  def pr(x:Double): Double
  def logpr(x:Double): Double
}
trait DiscreteGenerating {
  def length: Int
  def sampleInt: Int
  def pr(index:Int): Double
  def logpr(index:Int): Double
}
trait ProportionGenerating {
  def sampleProportions: Proportions
  def pr(p:Proportions): Double
  def logpr(p:Proportions): Double
}




/*class Binomial(p:RealValueParameter, trials:Int) extends OrdinalVariable with GeneratedVariable {
  this := 0
}*/
trait GeneratedDiscreteValue extends GeneratedValue with DiscreteValue {
  def proportions: Proportions
  def parents = List(proportions)
  def pr: Double = proportions(this.intValue)
  // override def setByIndex(i:Int)(implicit d:DiffList): Unit = proportions match { case m:DenseDirichletMultinomial => { m.increment(i, -1.0); super.setByIndex(i); m.increment(i, ,1.0) }; case _ => super.setByIndex(i) } // TODO This would be too slow, right?
  //def ~(proportions:Proportions): this.type = { proportions_=(proportions)(null); this }
}
trait GeneratedDiscreteVariable extends DiscreteVariable with GeneratedVariable with GeneratedDiscreteValue {
  def sample(implicit d:DiffList): Unit = setByIndex(proportions.sampleInt)
  def sampleFrom(parents:Seq[Variable])(implicit d:DiffList) = parents match {
    case Seq(p:Proportions) => setByIndex(p.sampleInt)
  }
  def maximize(implicit d:DiffList): Unit = setByIndex(proportions.maxPrIndex)
}
// A Discrete ~ Multinomial(Proportions), in which we can change the parent
class Discrete(p:Proportions, value:Int = 0) extends DiscreteVariable(value) with GeneratedDiscreteVariable {
  //assert(p.length <= domainSize)
  private val proportionsRef = new ParameterRef(p, this)
  def proportions = proportionsRef.value
  def proportions_=(p2:Proportions)(implicit d:DiffList = null) = { assert(p2.length <= domainSize); proportionsRef.set(p2) }
  override def parentRefs = List(proportionsRef)
}
trait GeneratedCategoricalValue[A] extends GeneratedDiscreteValue with CategoricalValue[A]
trait GeneratedCategoricalVariable[A] extends CategoricalVariable[A] with GeneratedDiscreteVariable with GeneratedCategoricalValue[A]
class Categorical[A](p:Proportions, value:A) extends CategoricalVariable(value) with GeneratedCategoricalVariable[A] {
  //assert(p.length <= domainSize)
  private val proportionsRef = new ParameterRef(p, this)
  def proportions = proportionsRef.value
  def proportions_=(p2:Proportions)(implicit d:DiffList) = { assert(p2.length <= domainSize); proportionsRef.set(p2) }
  override def parentRefs = List(proportionsRef)
}
class ObservedDiscrete(p:Proportions, value:Int) extends DiscreteObservation(value) with GeneratedValue {
  // TODO Rename "DiscreteConstant"?
  //assert(p.length <= domainSize)
  private val proportionsRef = new ParameterRef(p, this)
  def proportions = proportionsRef.value
  def proportions_=(p2:Proportions)(implicit d:DiffList) = { assert(p2.length <= domainSize); proportionsRef.set(p2) }
  def parents = List(proportionsRef.value)
  override def parentRefs = List(proportionsRef)
  def pr: Double = proportions(this.intValue)
}
class ObservedDiscretes(val proportions:Proportions, values:Traversable[Int] = Nil) extends DiscreteValues with GeneratedValue with ConstantValue {
  assert(proportions.length <= domainSize)
  proportions.addChild(this)(null)
  def parents = List(proportions)
  private val _values = values.toArray
  override def logpr: Double = { var result = 0.0; forIndex(_values.size)(index => result += math.log(proportions(index))); result }
  def pr: Double = math.exp(logpr)
  def vector: Vector = throw new Error
}


// Templates
class GeneratedValueTemplate extends TemplateWithStatistics3ss[GeneratedValue,AbstractParameterRef,Parameter] {
  def unroll1(v:GeneratedValue) = Factor(v, v.parentRefs, v.parents)
  def unroll2(r:AbstractParameterRef) = Factor(r.child, r.child.parentRefs, r.child.parents)
  def unroll3(p:Parameter) = p.children.map(v => Factor(v, v.parentRefs, v.parents))
  def score(s:Stat) = s.s1.logpr
}


// LDA, PyMC style
/*
class Word(s:String, ps:Seq[Proportions] = Nil, z:MixtureChoice = null) extends CategoricalMixtureObservation(ps, z, s)
class Document extends ArrayList[Word] { val theta: Proportions }
class Z(p:Proportions[Z]) extends MixtureChoice[Z](p)
// Read data
val nTopics = 10
val topics = repeat(nTopics) new SymmetricDirichlet(Domain[Word].size)(0.01)
for (i <- 0 until document.length) {
  document.theta = new SymmetricDirichlet[Z](1.0)
  for (word <- document) {
    val z = new MixtureChoice(document.theta)
    word ~ (topics, z) // val word = new Word(string, topics, z)
    //word ~ new Word(string, topics, z)
  }
}
*/