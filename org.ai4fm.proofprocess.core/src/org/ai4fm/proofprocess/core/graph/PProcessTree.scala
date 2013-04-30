package org.ai4fm.proofprocess.core.graph

/** A bridge to ProofProcess tree structure.
  * 
  * Provides extractors and factories for each type of ProofProcess tree elements:
  * entry, sequence, parallel and decorator.
  * 
  * Allows hiding the actual implementation of the ProofProcess tree, e.g. either EMF
  * or Scala-based, or otherwise. The structure can be examined using the extractors 
  * in pattern matching, and created using the factory methods.
  * 
  * @author Andrius Velykis
  */
trait PProcessTree[Elem, Entry <: Elem, Seq <: Elem, Parallel <: Elem, Decor <: Elem, EntryData] {

  import PProcessTree._
  
  def entry: CaseObject[Elem, Entry, EntryData]
  
  def seq: CaseObject[Elem, Seq, List[Elem]]
  
  def parallel: CaseObject[Elem, Parallel, (Set[Elem], Set[Entry])]
  
  def decor: CaseObject[Elem, Decor, Elem]
}

object PProcessTree {
  trait Extractor[Elem, Contents] {
    def unapply(e: Elem): Option[Contents]
  }
  
  type Factory[ActualElem, Contents] = Function[Contents, ActualElem]
  trait CaseObject[Elem, Actual <: Elem, Contents]
    extends Extractor[Elem, Contents] with Factory[Actual, Contents]
}
