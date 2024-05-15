/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal.util.appmacro

import sbt.internal.util.Types
import scala.quoted.*

/**
 * Convert is a glorified partial function to scan through the AST for the purpose of substituting
 * the matching term with something else.
 *
 * This is driven by calling transformWrappers(...) method. The filtering is limited to the shape of
 * code matched using `appTransformer`, which is a generic function with a single type param and a
 * single term param like `X.wrapInit[A](...)`.
 */
trait Convert[C <: Quotes & Singleton] extends ContextUtil[C]:
  import qctx.reflect.*

  def convert(term: WrappedTerm): Converted

  def asPredicate: WrappedTerm => Boolean = term => convert(term).isSuccess

  /**
   * Substitutes wrappers in tree `t` with the result of `subWrapper`. A wrapper is a Tree of the
   * form `f[T](v)` for which isWrapper(<Tree of f>, <Underlying Type>, <qual>.target) returns true.
   * Typically, `f` is a `Select` or `Ident`. The wrapper is replaced with the result of
   * `subWrapper(<Type of T>, <Tree of v>, <wrapper Tree>)`
   */
  def transformWrappers(tree: Term, subWrapper: WrappedTerm => Converted, owner: Symbol): Term =

    // the main tree transformer that replaces calls to InputWrapper.wrap(x) with
    //  plain Idents that reference the actual input value
    object appTransformer extends TreeMap:
      override def transformTerm(tree: Term)(owner: Symbol): Term =
        WrappedTerm
          .unapply(tree)
          .map(subWrapper)
          .collect {
            case Converted.Success(tree, finalTransform) => finalTransform(tree)
            case Converted.Failure(position, message)    => report.errorAndAbort(message, position)
          }
          .getOrElse(super.transformTerm(tree)(owner))
    end appTransformer
    appTransformer.transformTerm(tree)(owner)

  case class WrappedTerm(name: String, tpe: TypeRepr, qual: Term, oldTree: Term)
  object WrappedTerm:
    def unapply(tree: Term): Option[WrappedTerm] =
      tree match
        case Apply(TypeApply(Select(_, nme), targ :: Nil), qual :: Nil) =>
          Some(WrappedTerm(nme, targ.tpe, qual, tree))
        case Apply(TypeApply(Ident(nme), targ :: Nil), qual :: Nil) =>
          Some(WrappedTerm(nme, targ.tpe, qual, tree))
        case _ => None
  end WrappedTerm

  object Converted:
    def success(tree: Term) = Converted.Success(tree, Types.idFun)

  enum Converted:
    def isSuccess: Boolean = this match
      case Success(_, _) => true
      case _             => false

    def transform(f: Term => Term): Converted = this match
      case Success(tree, finalTransform) => Success(f(tree), finalTransform)
      case x: Failure                    => x
      case x: NotApplicable              => x

    case Success(tree: Term, finalTransform: Term => Term) extends Converted
    case Failure(position: Position, message: String) extends Converted
    case NotApplicable() extends Converted
  end Converted
end Convert
