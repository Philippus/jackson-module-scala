package tools.jackson.module.scala.introspect

import com.fasterxml.jackson.annotation.JsonProperty
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.Outcome
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers
import tools.jackson.core.JsonGenerator
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind
import tools.jackson.databind.`type`.ClassKey
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.util.LookupCache
import tools.jackson.databind.{BeanDescription, MapperFeature, ObjectMapper, SerializerProvider, ValueSerializer}
import tools.jackson.module.scala.deser.OptionWithNumberDeserializerTest.OptionLong
import tools.jackson.module.scala.DefaultScalaModule

import scala.beans.BeanProperty
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

object ScalaAnnotationIntrospectorTest {
  class Token
  class BasicPropertyClass(val param: Int)
  class BeanPropertyClass(@BeanProperty val param: Int)
  class AnnotatedBasicPropertyClass(@JsonScalaTestAnnotation val param: Token)
  class AnnotatedBeanPropertyClass(@JsonScalaTestAnnotation @BeanProperty val param: Token)
  class JavaBeanPropertyClass {
    private var value: Int = 0
    @JsonProperty def getValue: Int = value
    @JsonProperty def setValue(value: Int): Unit = { this.value = value }
  }

  case class CaseClassWithDefault(a: String = "defaultParam", b: Option[String] = Some("optionDefault"), c: Option[String])

  case class ConcurrentLookupCache(cache: TrieMap[ClassKey, BeanDescriptor] = TrieMap.empty[ClassKey, BeanDescriptor])
    extends LookupCache[ClassKey, BeanDescriptor] {

    override def put(key: ClassKey, value: BeanDescriptor): BeanDescriptor =
      cache.put(key, value).getOrElse(None.orNull)

    override def putIfAbsent(key: ClassKey, value: BeanDescriptor): BeanDescriptor =
      cache.putIfAbsent(key, value).getOrElse(None.orNull)

    override def get(key: Any): BeanDescriptor = key match {
      case classKey: ClassKey => cache.get(classKey).getOrElse(None.orNull)
      case _ => None.orNull
    }

    override def clear(): Unit = {
      cache.clear()
    }

    override def size: Int = cache.size

    override def snapshot(): LookupCache[ClassKey, BeanDescriptor] = {
      val newCache = TrieMap.empty[ClassKey, BeanDescriptor]
      cache.foreach { case (k, v) =>
        newCache.put(k, v)
      }
      ConcurrentLookupCache(newCache)
    }
  }
}

class ScalaAnnotationIntrospectorTest extends FixtureAnyFlatSpec with Matchers {
  import ScalaAnnotationIntrospectorTest._

  type FixtureParam = ObjectMapper

  override def withFixture(test: OneArgTest): Outcome = {
    val builder = new JsonMapper.Builder(new JsonFactory).addModule(DefaultScalaModule)
    val mapper = builder.build()
    withFixture(test.toNoArgTest(mapper))
  }

  behavior of "ScalaAnnotationIntrospector"

  it should "detect a val property" in { mapper =>
    val bean = new BasicPropertyClass(1)
    val allProps = getProps(mapper, bean)
    allProps.loneElement should have (Symbol("name") ("param"))

    val prop = allProps.asScala.head
    prop should have (
      Symbol("hasField") (true),
      Symbol("hasGetter") (true),
      Symbol("hasConstructorParameter") (true)
    )

    val accessor = prop.getAccessor
    accessor shouldNot be (null)
  }

  it should "detect annotations on a val property" in { mapper =>
    val builder = new JsonMapper.Builder(new JsonFactory)
      .addModule(DefaultScalaModule)
      .addModule(new SimpleModule() {
      addSerializer(new ValueSerializer[Token] {
        override val handledType: Class[Token] = classOf[Token]
        override def serialize(value: Token, gen: JsonGenerator, serializers: SerializerProvider): Unit =
          gen.writeString("")
        override def createContextual(prov: SerializerProvider, property: databind.BeanProperty): ValueSerializer[_] = {
          property.getAnnotation(classOf[JsonScalaTestAnnotation]) shouldNot be (null)
          this
        }
      })
    })

    val bean = new AnnotatedBasicPropertyClass(new Token)
    builder.build().writeValueAsString(bean)
  }

  it should "detect a bean property" in { mapper =>
    val bean = new BeanPropertyClass(1)
    val allProps = getProps(mapper, bean)
    allProps.loneElement should have (Symbol("name") ("param"))

    val prop = allProps.asScala.head
    prop should have (
      Symbol("hasField") (true),
      Symbol("hasGetter") (true),
      Symbol("hasConstructorParameter") (true)
    )

    val accessor = prop.getAccessor
    accessor shouldNot be (null)
  }

  it should "detect annotations on a bean property" in { mapper =>
    val builder = new JsonMapper.Builder(new JsonFactory).addModule(new SimpleModule() {
      addSerializer(new ValueSerializer[Token] {
        override val handledType: Class[Token] = classOf[Token]
        override def serialize(value: Token, gen: JsonGenerator, serializers: SerializerProvider): Unit =
          gen.writeString("")
        override def createContextual(prov: SerializerProvider, property: databind.BeanProperty): ValueSerializer[_] = {
          property.getAnnotation(classOf[JsonScalaTestAnnotation]) shouldNot be (null)
          this
        }
      })
    })

    val bean = new AnnotatedBeanPropertyClass(new Token)
    val allProps = getProps(mapper, bean)
    allProps.loneElement should have (Symbol("name") ("param"))

    val prop = allProps.asScala.head
    prop should have (
      Symbol("hasField") (true),
      Symbol("hasGetter") (true),
      Symbol("hasConstructorParameter") (true)
    )
    val param = prop.getConstructorParameter
    param.getAnnotation(classOf[JsonScalaTestAnnotation]) shouldNot be (null)

    //TODO fix test (works in v2.12.0)
    //builder.build().writeValueAsString(bean)
  }

  it should "correctly infer name(s) of un-named bean properties" in { mapper =>
    val tree = mapper.valueToTree[databind.node.ObjectNode](new JavaBeanPropertyClass)
    tree.has("value") shouldBe true
    tree.has("setValue") shouldBe false
    tree.has("getValue") shouldBe false
  }

  it should "respect APPLY_DEFAULT_VALUES true" in { _ =>
    val builder = JsonMapper.builder().enable(MapperFeature.APPLY_DEFAULT_VALUES).addModule(DefaultScalaModule)
    val mapper = builder.build()

    val json = """
        |{}
        |""".stripMargin

    val jsonWithKey = """
                 |{"a": "notDefault"}
                 |""".stripMargin

    val jsonWithNulls = """
                          |{"a": null, "b": null, "c": null}
                          |""".stripMargin

    val withDefault = mapper.readValue(json, classOf[CaseClassWithDefault])
    val withoutDefault = mapper.readValue(jsonWithKey, classOf[CaseClassWithDefault])
    val withNulls = mapper.readValue(jsonWithNulls, classOf[CaseClassWithDefault])


    withDefault.a shouldBe "defaultParam"

    withoutDefault.a shouldBe "notDefault"

    withNulls.a shouldBe "defaultParam"
    withNulls.b shouldBe Some("optionDefault")
    withNulls.c shouldBe None
  }

  it should "respect APPLY_DEFAULT_VALUES false" in { _ =>
    val builder = JsonMapper.builder().disable(MapperFeature.APPLY_DEFAULT_VALUES).addModule(DefaultScalaModule)
    val mapper = builder.build()

    val json = """
                 |{}
                 |""".stripMargin

    val jsonWithKey = """
                        |{"a": "notDefault"}
                        |""".stripMargin

    val jsonWithNulls = """
                        |{"a": null, "b": null, "c": null}
                        |""".stripMargin

    val withDefault = mapper.readValue(json, classOf[CaseClassWithDefault])
    val withoutDefault = mapper.readValue(jsonWithKey, classOf[CaseClassWithDefault])
    val withNulls = mapper.readValue(jsonWithNulls, classOf[CaseClassWithDefault])

    withDefault.a shouldBe null

    withoutDefault.a shouldBe "notDefault"

    withNulls.a shouldBe null
    withNulls.b shouldBe None
    withNulls.c shouldBe None
  }

  it should "register and retrieve reference type override" in { _ =>
    val caseClass = classOf[OptionLong]
    val refClass = classOf[Long]
    ScalaAnnotationIntrospectorModule.getRegisteredReferencedValueType(caseClass, "value") shouldBe empty
    try {
      ScalaAnnotationIntrospectorModule.registerReferencedValueType(caseClass, "value", refClass)
      ScalaAnnotationIntrospectorModule.getRegisteredReferencedValueType(caseClass, "value") shouldEqual Some(refClass)
    } finally {
      ScalaAnnotationIntrospectorModule.clearRegisteredReferencedTypes(caseClass)
      ScalaAnnotationIntrospectorModule.getRegisteredReferencedValueType(caseClass, "value") shouldBe empty
    }
  }

  it should "allow descriptor cache to be replaced" in { _ =>
    val cache = ConcurrentLookupCache()
    ScalaAnnotationIntrospectorModule.setDescriptorCache(cache)
    val builder = JsonMapper.builder().addModule(DefaultScalaModule)
    val mapper = builder.build()
    val jsonWithKey = """{"a": "notDefault"}"""

    val withoutDefault = mapper.readValue(jsonWithKey, classOf[CaseClassWithDefault])
    withoutDefault.a shouldEqual "notDefault"

    cache.size shouldBe >=(1)
    cache.get(new ClassKey(classOf[CaseClassWithDefault])) should not be(null)
  }

  private def getProps(mapper: ObjectMapper, bean: AnyRef) = {
    val classIntrospector = mapper.serializationConfig().classIntrospectorInstance()
    val beanDescription: BeanDescription = classIntrospector.introspectForSerialization(mapper.constructType(bean.getClass))
    beanDescription.findProperties()
  }
}