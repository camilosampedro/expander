package expander.ast

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ Matchers, OptionValues, TryValues, WordSpec }
import play.api.libs.json._

class PathRequestSpec extends WordSpec with Matchers with ScalaFutures with TryValues
    with OptionValues {

  def parse(s: String) = PathRequest.parse(s).toSet

  def one(s: String) = parse(s).head

  "parser" should {
    "parse jspath" in {
      parse("user") shouldBe Set(PathRequest(__ \ "user"))
      parse(".user .some") shouldBe Set(PathRequest(__ \ "user" \ "some"))
      parse(".user * some") shouldBe Set(PathRequest(__ \ "user" \\ "some"))
      parse(".user. some*other") shouldBe Set(PathRequest(__ \ "user" \ "some" \\ "other"))
      parse(". user[ 1]") shouldBe Set(PathRequest(__ \ "user" apply 1))
    }

    "parse params" in {
      parse(". user( name : value)") shouldBe Set(PathRequest(__ \ "user", Map("name" → "value")))
      parse(".user\t*\nsome ( name:value ,test :some)") shouldBe Set(PathRequest(__ \ "user" \\ "some", Map("name" → "value", "test" → "some")))
    }

    "parse seq" in {
      parse(".user,test, .some*thing") shouldBe Set(PathRequest(__ \ "user"), PathRequest(__ \ "test"), PathRequest(__ \ "some" \\ "thing"))
      parse(".user  (a:b)\n,test(c:d,e:f),.some*thing") shouldBe Set(PathRequest(__ \ "user", Map("a" → "b")), PathRequest(__ \ "test", Map("c" → "d", "e" → "f")), PathRequest(__ \ "some" \\ "thing"))
    }

    "parse subfields" in {
      parse("user  {test}") shouldBe Set(PathRequest(__ \ "user" \ "test"))
      parse("user{test,some}") shouldBe Set(PathRequest(__ \ "user" \ "test"), PathRequest(__ \ "user" \ "some"))
      parse("user(a:b  ){test,some}") shouldBe Set(PathRequest(__ \ "user", Map("a" → "b"), Seq(PathRequest(__ \ "test"), PathRequest(__ \ "some"))))
      parse("user(a:b) *    in{test,some}") shouldBe Set(PathRequest(__ \ "user", Map("a" → "b"), Seq(PathRequest(__ \\ "in" \ "test"), PathRequest(__ \\ "in" \ "some"))))
      parse("user(a:b){test(  c :d),some}") shouldBe Set(PathRequest(__ \ "user", Map("a" → "b"), Seq(PathRequest(__ \ "test", Map("c" → "d")), PathRequest(__ \ "some"))))
      parse("user(a :b  ){  test(c:d)   *sub   ,some}") shouldBe Set(PathRequest(__ \ "user", Map("a" → "b"), Seq(PathRequest(__ \ "test", Map("c" → "d"), Seq(PathRequest(__ \\ "sub"))), PathRequest(__ \ "some"))))
    }

    "fold duplicates" in {
      parse("user,user") shouldBe Set(PathRequest(__ \ "user"))
      parse("user.test,user.test") shouldBe Set(PathRequest(__ \ "user" \ "test"))
      parse("user,user.test") shouldBe Set(PathRequest(__ \ "user" \ "test"))
      parse("user(a:b),user.test") shouldBe Set(PathRequest(__ \ "user", Map("a" → "b"), Seq(PathRequest(__ \ "test"))))
    }

    "stringify result correctly" in {
      parse("user").mkString(",") shouldBe ".user"
      parse("user.field").mkString(",") shouldBe ".user.field"
      parse("user(a:b).field").mkString(",") shouldBe ".user(a:b).field"
      parse("user(a:b).field(c:d)").mkString(",") shouldBe ".user(a:b).field(c:d)"
      parse("user(a:b).field(c:d),user.field").mkString(",") shouldBe ".user(a:b).field(c:d)"
      parse("user(a:b){field(c:d),user.field}").mkString(",") shouldBe ".user(a:b){.field(c:d),.user.field}"
    }

    "gen match params" in {
      one("user").matchParams(__ \ "user") shouldBe Some(Map())
      one("user").matchParams(__ \ "tail") shouldBe empty
      one("user(a:b)").matchParams(__ \ "user") shouldBe Some(Map("a" → "b"))
      one("user(c:d).field.sub(a:b)").matchParams(__ \ "user") shouldBe Some(Map("c" → "d", "_expand" → ".field.sub(a:b)"))
      one("user*field.sub(a:b)").matchParams(__ \ "user" apply 0) shouldBe Some(Map("_expand" → ".field.sub(a:b)"))
      one("user(c:d)*field.sub(a:b)").matchParams(__ \ "user" apply 0) shouldBe Some(Map("c" → "d", "_expand" → ".field.sub(a:b)"))
    }
  }

}
