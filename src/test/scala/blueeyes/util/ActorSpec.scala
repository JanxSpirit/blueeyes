package blueeyes.util

import org.spex.Specification


class ActorSpec extends Specification with ActorExecutionStrategySequential {
  "Actor: process message" in {
    val actor = Actor[String, String] {
      case message: String => message + "_done"
    }

   actor("foo").value must eventually (beSome("foo_done"))
  }

  "Actor examples must compile" in {
    // Easy actor definition:
    val squarePositiveA = Actor[Int, Int] {
      case x: Int if (x > 0) => x * x
    }

    val squareNegativeA = Actor[Int, Int] {
      case x: Int if (x < 0) => x * x
    }

    // Constant actors:
    val zeroA = Actor.constant[Int, Int](0)

    // Actor composition based on partial functions:
    val squareAnythingA = squarePositiveA.orElse(squareNegativeA).orElse(zeroA)

    // Actor composition based on arrows:
    val pow4A = squareAnythingA >>> squareAnythingA

    // Actor response mapping:
    val pow4AsStringA = pow4A.map(_.toString)

    pow4AsStringA(0).value must eventually (beSome("0"))
    pow4AsStringA(1).value must eventually (beSome("1"))
    pow4AsStringA(2).value must eventually (beSome("16"))
  }
}