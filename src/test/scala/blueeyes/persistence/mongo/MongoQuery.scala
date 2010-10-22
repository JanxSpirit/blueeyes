package blueeyes.persistence.mongo

import blueeyes.json._
import blueeyes.json.JsonAST._

import blueeyes.util.ProductPrefixUnmangler

object MongoQueryOperators {
  sealed trait MongoQueryOperator extends Product with ProductPrefixUnmangler {
    def symbol: String = unmangledName
  
    def unary_! : MongoQueryOperator
    
    override def toString = symbol
  }
  case object $gt     extends MongoQueryOperator  { def unary_! = $lte; }
  case object $gte    extends MongoQueryOperator  { def unary_! = $lt; }
  case object $lt     extends MongoQueryOperator  { def unary_! = $gte; }
  case object $lte    extends MongoQueryOperator  { def unary_! = $gt; }
  case object $eq     extends MongoQueryOperator { def unary_! = $ne; } // This is a virtual operator, it's not real!!!!
  case object $ne     extends MongoQueryOperator { def unary_! = $eq; }
  case object $in     extends MongoQueryOperator { def unary_! = $nin; }
  case object $nin    extends MongoQueryOperator { def unary_! = $in; }
  case object $mod    extends MongoQueryOperator { def unary_! = error("The $mod operator does not have a negation"); }
  case object $all    extends MongoQueryOperator { def unary_! = error("The $all operator does not have a negation"); }
  case object $size   extends MongoQueryOperator { def unary_! = error("The $size operator does not have a negation"); }
  case object $exists extends MongoQueryOperator { def unary_! = error("The $exists operator does not have a negation"); }
  case object $type   extends MongoQueryOperator { def unary_! = error("The $type operator does not have a negation"); }
  case object $or     extends MongoQueryOperator { def unary_! = error("The $or operator does not have a negation"); }
}

trait MongoQuery { self =>
  def query: JValue
  
  def & (that: MongoQuery): MongoQuery =  this && that
  
  def && (that: MongoQuery): MongoQuery = error("not implemented")

  def | (that: MongoQuery): MongoQuery = this || that
  
  def || (that: MongoQuery): MongoQuery = error("not implemented")
  
  def unary_! : MongoQuery = error("not implemented")
}

trait MongoSimpleQuery extends MongoQuery { self =>
  def elements = self :: Nil
  
  def jpath: JPath
  
  def combinesWith(that: MongoSimpleQuery): Boolean = (self.jpath == that.jpath)
  
  def * (that: MongoSimpleQuery): MongoSimpleQuery
}


sealed trait MongoPrimitive[T] {
  def toMongoValue: Any
}

sealed class MongoPrimitiveWitness[T]

case class MongoQueryBuilder(jpath: JPath) {
  def === [T](value: MongoPrimitive[T]): MongoQuery = error("not implemented")
  
  def !== [T](value: MongoPrimitive[T]): MongoQuery = error("not implemented")
  
  def > (value: Long): MongoQuery = error("not implemented")
  
  def >= (value: Long): MongoQuery = error("not implemented")
  
  def < (value: Long): MongoQuery = error("not implemented")
  
  def <= (value: Long): MongoQuery = error("not implemented")
  
  def in [T <: MongoPrimitive[T]](items: T*): MongoQuery = error("not implemented")
  
  def contains [T <: MongoPrimitive[T]](items: T*): MongoQuery = error("not implemented")
  
  def hasSize(length: Int): MongoQuery = error("not implemented")
  
  def exists: MongoQuery = error("not implemented")
  
  def hasType[T](implicit witness: MongoPrimitiveWitness[T]): MongoQuery = error("not implemented")
}


trait MongoQueryImplicits {
  import MongoQueryOperators._ 
  
  implicit def mongoOperatorToSymbolString(op: MongoQueryOperator): String = op.symbol
  
  implicit def stringToMongoQueryBuilder(string: String): MongoQueryBuilder = MongoQueryBuilder(JPath(string))
  
  implicit def jpathToMongoQueryBuilder(jpath: JPath): MongoQueryBuilder = MongoQueryBuilder(jpath)
  
  case class MongoPrimitiveString(value: String) extends MongoPrimitive[String] {
    def toMongoValue = new java.lang.String(value)
  }
  
  implicit def stringToMongoPrimitiveString(value: String) = MongoPrimitiveString(value)
  
  /*
  Double	 1
  String	 2
  Object	 3
  Array	 4
  Binary data	 5
  Object id	 7
  Boolean	 8
  Date	 9
  Null	 10
  Regular expression	 11
  JavaScript code	 13
  Symbol	 14
  JavaScript code with scope	 15
  32-bit integer	 16
  Timestamp	 17
  64-bit integer	 18
  Min key	 255
  Max key	 127
  */
  implicit val MongoPrimitiveJStringWitness = new MongoPrimitiveWitness[JString]
  implicit val MongoPrimitiveJDoubleWitness = new MongoPrimitiveWitness[JDouble]
  implicit val MongoPrimitiveJObjectWitness = new MongoPrimitiveWitness[JObject]
  implicit val MongoPrimitiveJArrayWitness = new MongoPrimitiveWitness[JArray]
  implicit val MongoPrimitiveJBoolWitness = new MongoPrimitiveWitness[JBool]
  implicit val MongoPrimitiveJNullWitness = new MongoPrimitiveWitness[JNull.type]
  implicit val MongoPrimitiveJIntWitness = new MongoPrimitiveWitness[JInt]
  

    // case class MongoPrimitiveString    - def toMongoValue: String
    // case class MongoPrimitiveLong      - def toMongoValue: java.lang.Long
    // case class MongoPrimitiveInteger   - def toMongoValue: java.lang.Integer
    // case class MongoPrimitiveDouble    - def toMongoValue: java.lang.Double
    // case class MongoPrimitiveFloat     - def toMongoValue: java.lang.Float
    // case class MongoPrimitiveBoolean   - def toMongoValue: java.lang.Boolean
    // case class MongoPrimitiveArrayList - def toMongoValue: java.util.ArrayList[AnyRef]
    // case class MongoPrimitiveDBObject  - def toMongoValue: DBObject
    // case class MongoPrimitiveNull      - def toMongoValue: null
    // case class MongoPrimitiveObjectId  - def toMongoValue: com.mongodb.ObjectId
    // case class MongoPrimitivePattern   - def toMongoValue: java.util.regex.Pattern
    // case class MongoPrimitiveDate      - def toMongoValue: java.util.Date
    // case class MongoPrimitiveDBRef     - def toMongoValue: com.mongodb.DBRef
    // case class MongoPrimitiveByte      - def toMongoValue: byte[]
}
object MongoQueryImplicits extends MongoQueryImplicits