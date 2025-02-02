package com.linagora.tmail.mailets

import java.util
import java.util.Optional

import com.linagora.tmail.james.jmap.contact.{ContactFields, TmailContactUserAddedEvent}
import com.linagora.tmail.mailets.ContactsCollectionTest.{ATTRIBUTE_NAME, MAILET_CONFIG, RECIPIENT, RECIPIENT2, RECIPIENT3, SENDER}
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.apache.james.core.builder.MimeMessageBuilder
import org.apache.james.core.{MailAddress, Username}
import org.apache.james.events.EventListener.ReactiveGroupEventListener
import org.apache.james.events.delivery.InVmEventDelivery
import org.apache.james.events.{Event, EventBus, Group, InVMEventBus, MemoryEventDeadLetters, RetryBackoffConfiguration}
import org.apache.james.metrics.tests.RecordingMetricFactory
import org.apache.mailet.base.test.{FakeMail, FakeMailetConfig}
import org.apache.mailet.{AttributeName, Mail, MailetConfig, MailetException}
import org.assertj.core.api.Assertions.{assertThat, assertThatCode, assertThatThrownBy}
import org.junit.jupiter.api.{BeforeEach, Test}
import org.reactivestreams.Publisher
import reactor.core.scala.publisher.SMono

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

object ContactsCollectionTest {
  val ATTRIBUTE_NAME: AttributeName = AttributeName.of("AttributeValue1")
  val MAILET_CONFIG: MailetConfig = FakeMailetConfig.builder()
    .mailetName("ContactsCollection")
    .setProperty("attribute", ATTRIBUTE_NAME.asString())
    .build()

  val SENDER: String = "sender1@domain.tld"
  val RECIPIENT: String = "recipient1@domain.tld"
  val RECIPIENT2: String = "recipient2@domain.tld"
  val RECIPIENT3: String = "recipient3@domain.tld"
}

class TestEventListener(events: util.ArrayList[Event] = new util.ArrayList[Event]()) extends ReactiveGroupEventListener {

  override def reactiveEvent(event: Event): Publisher[Void] = SMono.just(events.add(event)).`then`()

  override def getDefaultGroup: Group = new Group

  def eventReceived(): util.List[Event] = events

  def contactReceived(): util.List[ContactFields] = events.asScala
    .map(_.asInstanceOf[TmailContactUserAddedEvent])
    .map(_.contact).asJava
}

class ContactsCollectionTest {
  var mailet: ContactsCollection = _
  var eventBus: EventBus = _
  var eventListener: TestEventListener = new TestEventListener()

  @BeforeEach
  def setup(): Unit = {
    eventBus = new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory), RetryBackoffConfiguration.DEFAULT, new MemoryEventDeadLetters())
    eventBus.register(eventListener)
    mailet = new ContactsCollection(eventBus)
  }

  @Test
  def initShouldThrowWhenNoAttributeParameter(): Unit = {
    val mailetConfig: FakeMailetConfig = FakeMailetConfig.builder()
      .mailetName("ContactsCollection")
      .build()

    assertThatThrownBy(() => mailet.init(mailetConfig))
      .isInstanceOf(classOf[MailetException])
  }

  @Test
  def initShouldNotThrowWithAllParameters(): Unit = {
    val mailetConfig: FakeMailetConfig = FakeMailetConfig.builder()
      .mailetName("ContactsCollection")
      .setProperty("attribute", "attributeValue")
      .build()

    assertThatCode(() => mailet.init(mailetConfig))
      .doesNotThrowAnyException()
  }

  @Test
  def serviceShouldDispatchEventWhenHasRecipient(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(RECIPIENT)
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)
    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)))
  }

  @Test
  def parsingAddressShouldRelyOnMime4J(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(s"destinataires inconnus: $RECIPIENT;")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient("whateverRecipient@domain.tld")
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)))
  }

  @Test
  def shouldNotFailWhenEmptyGroupAddress(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient("destinataires inconnus: ;")
        .addBccRecipient(RECIPIENT)
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)))
  }

  @Test
  def shouldSupportGroupAddress(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(s"destinataires inconnus: $RECIPIENT, $RECIPIENT2;")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipients(RECIPIENT, RECIPIENT2)
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)), ContactFields(new MailAddress(RECIPIENT2)))
  }

  @Test
  def mixedCaseGroupAddressInManyFields(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(s"group1: $RECIPIENT;")
        .addCcRecipient(s"group2: $RECIPIENT2;")
        .addBccRecipient(s"group3: $RECIPIENT3;")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipients(RECIPIENT, RECIPIENT2, RECIPIENT3)
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)), ContactFields(new MailAddress(RECIPIENT2)), ContactFields(new MailAddress(RECIPIENT3)))
  }

  @Test
  def mixedCaseGroupAddressAndMailboxAddress(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(s"group1: $RECIPIENT, $RECIPIENT2;", RECIPIENT3)
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipients(RECIPIENT, RECIPIENT2, RECIPIENT3)
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)), ContactFields(new MailAddress(RECIPIENT2)), ContactFields(new MailAddress(RECIPIENT3)))
  }

  @Test
  def shouldMergeContactsWhenDuplicated(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addHeader("To", s"group1: $RECIPIENT, $RECIPIENT2; $RECIPIENT3")
        .addHeader("Cc", s"$RECIPIENT, $RECIPIENT2, $RECIPIENT3")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipients(RECIPIENT, RECIPIENT2, RECIPIENT3)
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)), ContactFields(new MailAddress(RECIPIENT2)), ContactFields(new MailAddress(RECIPIENT3)))
  }

  @Test
  def shouldParseAddressNameIfPossible(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient("Christophe Hamerling <chri.hamerling@linagora.com>")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient("chri.hamerling@linagora.com")
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress("chri.hamerling@linagora.com"), "Christophe Hamerling"))
  }

  @Test
  def shouldParseAddressNamesInGroupIfPossible(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient("group: Christophe Hamerling <chri.hamerling@linagora.com>, Alpine Ubuntu <alpine.ubuntu@linagora.com>;")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipients("chri.hamerling@linagora.com", "alpine.ubuntu@linagora.com")
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress("chri.hamerling@linagora.com"), "Christophe Hamerling"),
        ContactFields(new MailAddress("alpine.ubuntu@linagora.com"), "Alpine Ubuntu"))
  }

  @Test
  def eventHasBeenDispatchedShouldHasUsernameIsSender(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(RECIPIENT)
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)

    assertThat(eventListener.eventReceived())
      .hasSize(1)
    assertThat(eventListener.eventReceived().get(0).getUsername)
      .isEqualTo(Username.of(SENDER))
  }

  @Test
  def serviceShouldDispatchSeveralEventWhenHasSeveralRecipient(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(RECIPIENT, "recipient2@domain.tld")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)), ContactFields(new MailAddress("recipient2@domain.tld")))
  }

  @Test
  def serviceShouldNotDispatchEventWhenMailHasNotRecipient(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .build()

    mailet.service(mail)
    assertThat(eventListener.eventReceived()).isEmpty()
  }

  @Test
  def serviceShouldAddTheAttribute(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(RECIPIENT)
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)

    val attributeValue = getAttributeValue(mail)
    assertThat(attributeValue).isPresent
    assertThatJson(attributeValue.get).isEqualTo(s"""{"userEmail":"$SENDER","emails":["$RECIPIENT"]}""")
  }

  @Test
  def serviceShouldNotAddAttributeWhenMailHasNotRecipient(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .build()

    mailet.service(mail)
    assertThat(getAttributeValue(mail)).isEmpty
  }

  @Test
  def serviceShouldCollectContactsFromCCRecipient(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(RECIPIENT)
        .addCcRecipient("cc@domain.tld")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)

    assertThatJson(getAttributeValue(mail).get)
      .isEqualTo(s"""{"userEmail":"$SENDER","emails":["$RECIPIENT", "cc@domain.tld"]}""")

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)), ContactFields(new MailAddress("cc@domain.tld")))
  }

  @Test
  def serviceShouldCollectContactsFromBCCRecipient(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(RECIPIENT)
        .addBccRecipient("bcc@domain.tld")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)

    assertThatJson(getAttributeValue(mail).get)
      .isEqualTo(s"""{"userEmail":"$SENDER","emails":["$RECIPIENT", "bcc@domain.tld"]}""")

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT)), ContactFields(new MailAddress("bcc@domain.tld")))
  }


  @Test
  def serviceShouldPreserveRecipientsEmailAddress(): Unit = {
    mailet.init(MAILET_CONFIG)

    val mail: FakeMail = FakeMail.builder()
      .name("mail1")
      .mimeMessage(MimeMessageBuilder.mimeMessageBuilder()
        .setSender(SENDER)
        .addToRecipient(s"RecipientName1 <$RECIPIENT>")
        .addCcRecipient("cc@domain.tld")
        .setSubject("Subject 01")
        .setText("Content mail 123"))
      .sender(SENDER)
      .recipient(RECIPIENT)
      .build()

    mailet.service(mail)

    assertThatJson(getAttributeValue(mail).get)
      .isEqualTo(s"""{"userEmail":"$SENDER","emails":["$RECIPIENT", "cc@domain.tld"]}""")

    assertThat(eventListener.contactReceived())
      .containsExactlyInAnyOrder(ContactFields(new MailAddress(RECIPIENT), firstname = "RecipientName1"), ContactFields(new MailAddress("cc@domain.tld")))
  }

  private def getAttributeValue(mail: Mail): Optional[String] =
    mail.getAttribute(ATTRIBUTE_NAME)
      .toScala.map(_.getValue.value().asInstanceOf[String])
      .toJava
}
