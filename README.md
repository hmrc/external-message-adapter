# external-message-adapter

This README.md file gives all the information about this micro-service along with its dependencies.

## Run the tests and sbt fmt before raising a PR

Format:

`sbt fmt`

Then run the tests and coverage report:

`sbt clean coverage test coverageReport`

If your build fails due to poor test coverage, *DO NOT* lower the test coverage threshold, instead inspect the generated report located here on your local repo: `/target/scala-2.12/scoverage-report/index.html`

Then run the integration tests:

`sbt it:test`

## API

| Path                                      | Supported Methods | Description                                                                                                        |
|-------------------------------------------|-------------------|--------------------------------------------------------------------------------------------------------------------|
| ```/external/messages```                  | POST              | Add a new message [More...](#post-externalmessages)                                                                        |
| ```/external/messages/:id/content```      | GET               | **** Get message content by id [More...](#get-messagesid-content)                                                  |
| ```/message-process-eventhub-events```    | POST              | Sets the time a given message was read [More...](#post-message-process-eventhub-events)                                        |

## Endpoints

### POST /external/messages

Create a new message. This is for messages that contain html content in the body.

An example message creation request:

```json
{   
   "externalRef":{
      "id":"123412342314",
      "source":"gmc"
   },
   "recipient":{
      "taxIdentifier":{
         "name":"sautr",
         "value":"1234567890"
      },
      "name":{
         "title":"Mr",
         "forename":"William",
         "secondForename":"Harry",
         "surname":"Smith",
         "honours":"OBE"
      },
      "email":"someEmail@test.com"
   },
   "messageType":"mailout-batch",
   "subject":"Reminder to file a Self Assessment return",
   "content":"Some base64-encoded HTML",
   "validFrom":"2017-02-14",
   "alertQueue":"DEFAULT",
   "details":{
      "formId":"SA300",
      "issueDate":"2017-02-14",
      "statutory":true,
      "paperSent":false,
      "batchId":"1234567",
      "sourceData": "<base64 encoded source data that was used by GMC to create the message>",
      "replyTo": "5c0a57826b00006b0032d0db"
   }
}
```


| Field | Description | Optional |
| ---------------------------- | -----------------------------------------------------------------------------------------------------------------------------  | --------------- |
| externalRef.source | The name of the caller. EG: "gmc", "nps". | |
| externalRef.id | An id that makes sense to the caller. It can be used for callbacks. If the source is "gmc", it is used to trigger the reprint process.  Once a message has been created with this external ID, any subsequent messages with the same external ID will be rejected. | |
| recipient.taxIdentifier.name | The tax identifier name to which this message relates. Can be "sautr" or "nino". We will support more in the future | |
| recipient.taxIdentifier.value | The recipient customer's tax identifier value. | |
| recipient.name | The full name of the recipient. It is used for the salutation in the email notifications sent for that message. | |
| recipient.email | Optional field to provide an email address to send an alert (typically used if a preference does not exist). | |
| messageType | This is is the type of the message. "print-suppression-notification" was the old term which we will stop using. Use "mailout-batch" for GMC messages. | |
| subject | The message subject that will display in the secure inbox. | |
| content | The message content to display in the secure message itself, in HTML. Must be a valid base64 encoded HTML. This HTML must only be for the display of the text content of the message, with no specific styling. HMRC branding with CSS, images, etc, will be added by the message inbox front end. We will agree on a subset of HTML tags that are appropriate to use here. | |
| details | Data within this section is usually specific to the type of message | |
| details.formId | The form ID of the message that we are sending. MDTP will not need this for selecting a message template (as is the case with PAGW messages) but will need it for reporting and for selecting a specific email alert template if required. | |
| details.statutory | true/false to indicate whether or not this is a statutory message. Statutory messages have certain requirements, e.g. for Self Assessment messages MDTP needs to ensure delivery of the email alert otherwise needs to request a reprint. | |
| details.paperSent | true/false to indicate whether or not the caller also sent a paper copy of the same message. MDTP would not trigger a reprint if this is true. | |
| details.batchId | If this message is part of a "batch" or "print run" that has an ID, then specify this here. This will allow reporting on the delivery of these batches and their alerts. | true |
| details.issueDate | ???? | |
| details.sourceData | It is a valid base64 encoded string and passed back to GMC if an email alert bounces and GMC needs to recreate the paper letter to be posted. | |
| details.replyTo | The replyTo is the mongo ObjectId of the original message. | |
| details.threadId | UUID for a conversation. | |
| details.enquiryType | ????. | |
| details.properties | Json payload | |
| validFrom | The date on which this message becomes valid. It should not be displayed to the customer before this date. If this is not present then it is available to the customer immediately. | true |
| alertQueue | The email messaging queue to place the email alert for this message onto. If not provided, defaults to DEFAULT. It must be one of: "DEFAULT", "BACKGROUND" and "PRIORITY".| DEFAULT |
| issueDate | The date this message was created by the source system. If not provided, this defaults to the system date at the time the API is called.| |

The full schema for this can be found [SchemaV3](./docs/SchemaV3.md)

Responds with status code:

- 201 if the message is successfully created
- 400 (Bad Request) if the body is not as per the above definition
- 400 (Bad Request) if the tax identifier is not supported
- 400 (Bad Request) invalid replyTo
- 400 (Bad Request) if nino's don't match
- 409 (Conflict) if the message hash is a duplicate of an existing message


### GET /external/messages/:id/content

TODO

### POST /message-process-eventhub-events

TODO
