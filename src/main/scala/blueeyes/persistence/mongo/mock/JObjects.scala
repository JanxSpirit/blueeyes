package blueeyes.persistence.mongo.mock

import blueeyes.json.JPath
import blueeyes.json.JsonAST._
import blueeyes.persistence.mongo.JPathExtension._

private[mock] trait JObjectFields{
  def selectByPath(selectionPath: JPath, jobject: JObject, transformer: (JValue) => Option[JValue], jobjectRestorer: (JPath, JValue) => JValue) = jobject.get(selectionPath) match{
    case Nil             => None
    case x :: Nil        => transformer(x).map(jobjectRestorer(selectionPath, _))
    case _        => error("jpath which is select more then one value is not supported")
  }

  def selectFields(jobjects: List[JObject], selection : List[JPath], transformer: (JValue) => Option[JValue], jobjectRestorer: (JPath, JValue) => JValue) = {
    if (!selection.isEmpty) {
      val allJFields = jobjects.map(jobject => selection.map(selectByPath(_, jobject, transformer, jobjectRestorer)))
      allJFields.map(jfields => {
        val definedJFields = jfields.filter(_ != None).map(_.get)
        definedJFields.headOption.map(head => definedJFields.tail.foldLeft(head){(jobject, jfield) => jobject.merge(jfield).asInstanceOf[JObject]})
      }).filter(_ != None).map(_.get)
    } else jobjects
  }

  def selectExistingFields(jobjects: List[JObject], selection : List[JPath]) = {
    def updateValue(value: JValue) = value match{
      case JNothing => None
      case _ => Some(value)
    }
    selectFields(jobjects, selection, updateValue _, jvalueToJObject _)
  }

  def jvalueToJObject(path: JPath, value: JValue) = {
    val elements = toMongoField(path).split("\\.").reverse
    elements.tail.foldLeft(JObject(JField(elements.head, value) :: Nil)){(result, element) => JObject(JField(element, result) :: Nil)}
  }
}

private[mongo] class JObjectOrdering(path: JPath, weight: Int) extends Ordering[JObject]{
  def compare(o1: JObject, o2: JObject) = (o1.get(path), o2.get(path)) match {
    case (v1 :: Nil, v2 :: Nil) =>
      (v1, v2) match {
        case (JString(x1),  JString(x2)) => x1.compare(x2) * weight
        case (JInt(x1),     JInt(x2))    => x1.compare(x2) * weight
        case (JDouble(x1),  JDouble(x2)) => x1.compare(x2) * weight
        case (JDouble(x1),  JInt(x2))    => x1.compare(x2.doubleValue) * weight
        case (JInt(x1),     JDouble(x2)) => x1.doubleValue.compare(x2) * weight
        case (JBool(x1),    JBool(x2))   => x1.compare(x2) * weight
        case (JNull,        JNull)       => 0
        case (v,            JNull)       => 1
        case (JNull,        v)           => -1
        case (JNothing,     JNothing)    => 0
        case (v,            JNothing)       => 1
        case (JNothing,     v)           => -1
        case _ => error("differents elements cannot be ordered")
      }
    case _ => error("lists cannot be ordered")
  }
}