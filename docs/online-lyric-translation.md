# Online lyric translation

Online translation is **off by default**. In the lock-screen lyrics settings, choose
**Translation backend**, open **Credentials and languages**, save your own key and
language codes, then select **Original + translation**. The separate **Show
translations** switch controls visibility, not whether enabled online translation
makes requests.

## Providers

| Selection | API | Credentials / billing |
| --- | --- | --- |
| Custom · LibreTranslate | POST to your base URL + `/translate`, or an explicit `/translate` URL | Optional user/server API key; server privacy, pricing and quotas apply |
| Google Cloud Translation · v2 | `https://translation.googleapis.com/language/translate/v2` | Your Google Cloud API key; enable Cloud Translation API and billing, restrict the key to this API and set usage quotas |
| DeepL API · Free | `https://api-free.deepl.com/v2/translate` | Your DeepL **API Free** key; your account quota applies |
| DeepL API · Pro | `https://api.deepl.com/v2/translate` | Your DeepL **API Pro** key; usage is charged to your account |

A DeepL translator subscription is not an API subscription. No keys are bundled.
Official provider URLs are fixed; a saved custom URL is ignored while Google or
DeepL is selected. Switching providers clears the current key to prevent sending
one service's credentials to another; re-enter the appropriate key when switching
back. Your custom endpoint is retained.

Use provider-supported language codes: source `auto` omits the official API source
parameter, target defaults to `en`. Examples: `zh`, `ja`, `fr`; DeepL target
variants include `EN-US` and `EN-GB`. The settings check code syntax, not the
provider's current supported-language list. Before sending lyrics, the module skips
requests when configured source and target codes identify the same language. It
also skips a song when lightweight on-device script or common-word checks identify
the target language with high confidence. This is not a full language identifier:
uncertain lyrics are still sent rather than risk suppressing a needed translation.
Latin-script lyrics are not assumed to be English.

## Privacy and safety

- Enabling online mode sends the original lyric text (never romanisation or a
  source translation) to the selected service, including when a provider supplied a
  translation in another language. The online result replaces that provider translation.
  With **Show romanisations** enabled, a provider's romanisation is added beneath the
  online translation; otherwise it is hidden. Disabling online mode restores native
  secondary text. If an individual line cannot be translated, it has no secondary text
  while online mode is active. Background vocals are preserved, not separately uploaded.
- Google uses `X-Goog-Api-Key`; DeepL uses `Authorization: DeepL-Auth-Key …`.
  Keys are not added to official API URLs. Custom LibreTranslate uses JSON `api_key`.
- Prefer HTTPS for custom servers. HTTP is allowed for existing/local servers but
  exposes lyrics and credentials in transit. Custom URLs must not contain embedded
  credentials, query parameters or fragments.
- Credentials use the module's existing on-device state mechanism (not encrypted
  storage). Rooted access can read them. Backups **exclude API keys**.
- Billing/quotas are controlled by the user's provider account. Batches run
  asynchronously, without automated retries. Already-running requests cannot be
  cancelled, but obsolete track/settings jobs stop before their next batch.
- Failures leave original lyrics intact. Primary timestamps, syllable arrays,
  duet alignment and background-vocal objects are preserved. No synthetic translated
  word timing is created.
- Translation results have bounded in-memory and persistent caches, separate from the
  native lyric cache and partitioned by provider, endpoint, language pair and lyric
  text/timing. Persistent entries expire after 90 days and remain within a bounded
  cache directory; API keys are never written there.
  Settings changes restore native lyrics, then reapply the current provider. The
  translation cache is keyed by original lyric text and timing, not native secondary text.
  Replies for obsolete tracks, base lyrics or settings are discarded.

## State and backup migration

`lyrictrprovider` is saved and returned by the module probe, with stable IDs:
`custom`, `google`, `deepl_free`, `deepl_pro`. The bridge sends it with `lyrictrcfg`.
An absent/unknown ID defaults to `custom`, preserving legacy endpoint users and
leaving the existing online mode unchanged (off for new installs).

Backups contain `lyricsTranslateProvider`, endpoint, language pair and mode, but
not the key. Importing an old endpoint-based backup selects `custom`. Import keeps
an on-device key only if the provider is unchanged and, for custom servers, the
endpoint is unchanged; otherwise the key is cleared. A restored official provider
needs a key before it can issue requests.

## Validation without Android SDK

From the project root, run:

```sh
python tools/run-translation-tests.py --java-home 'path/to/JDK'
```

Omit `--java-home` if `java` and `javac` are on PATH. The runner downloads the
project's JUnit 4.13.2, Hamcrest 1.3 and org.json 20231013 test artifacts from
Maven Central into a temporary directory. It compiles only translation Java
classes and tests. The HTTP transport is replaced with a fail-closed test stub;
no lyrics, credentials or paid API requests are sent. No Gradle, Android SDK or
lint is involved. Compose UI and device state/probe behavior still need on-device
validation.

## Official references

- [Google Cloud v2 translate](https://cloud.google.com/translate/docs/reference/rest/v2/translate)
- [Google API key header authentication](https://cloud.google.com/docs/authentication/api-keys-use)
- [DeepL translate API and authentication](https://developers.deepl.com/api-reference/translate)

Google sends a `q` array with `format: text`; DeepL sends a `text` array and
uppercase language codes. Official responses map by documented input order to
stable local line IDs; wrong-length or malformed arrays are rejected to avoid
attaching a translation to the wrong timestamp. LibreTranslate retains its
existing stable-ID marker protocol.
