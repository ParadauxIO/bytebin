# CSFriendlyCorner: Bytebin
A "pastebin" service backed by simple RESTful API.

it's a "pastebin" in a very simplified sense. effectively, it:

* accepts (optionally compressed) post requests containing raw content
* saves the content to disk and caches it in memory
* returns a key to indicate the location of the content
* serves content (in a compressed form if the client can handle it) when requested

there's a very minimal HTML frontend for posting content.

the primary intended purpose of bytebin is to act as a middle man in the communication of two separate clients, using payload objects (uploaded to a bytebin instance) as a means to transmit data.

it's also quite good for transferring or sharing large log/plain text files because they're particularly compressible with gzip.

## API Documentation 

**N.B**: This API supports CORS. (Cross-origin resource sharing)

### Reading
* You can view the contents of a bytebin post using it's key by navigating to `/{key}`.

### Posting Content
**N.B**: Herein `{key}` refers to the uniquely identifiable key at which content is stored

* Send a POST request to `/post`. The request body must not be empty.
* It's requested you set `Content-Type` and `User-Agent` headers but they're not necessarily required.  
* Ideally, content should be compressed with GZIP before being uploaded. Include the `Content-Encoding: gzip` header should this is the case.
* The key is specified in the returned `Location` header.
* The response body is a JSON object with only one property, `{"key": "{key}"}`.

## Public Instances

* This repository represents the bytebin instance at https://paste.csfriendlycorner.com
* This repository is a reskinned and tweaked version of bytebin by lucko, you can use Luck's instance @ https://bytebin.lucko.me provided you follow his fair-use guidelines.

## Fair use guidelines
The following apply to Luck's and my instance of bytebin.
* Don't be malicious
* Do not needlessly spam the API.
* Do not use the API to host content which is regarded as Illegal.
* Provide a `User-Agent` uniquely identifying your application
* Keep disk-usage low.

## How does it work?

bytebin uses:

* [rapidoid](https://www.rapidoid.org/) as a web server
* [caffeine](https://github.com/ben-manes/caffeine) to cache content & handle rate limits
* [guava](https://github.com/google/guava) for byte stream manipulation
* [gson](https://github.com/google/gson) to read the config

and plain old java for everything else.

## Licensing
Bytebin is made available under MIT.
