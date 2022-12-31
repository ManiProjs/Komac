package data.shared

import Errors
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightWhite
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import data.DefaultLocaleManifestData
import data.InstallerManifestData
import data.PreviousManifestData
import data.locale.LocaleUrl
import hashing.Hashing
import hashing.Hashing.hash
import input.PromptType
import input.Prompts
import io.ktor.client.request.head
import io.ktor.http.isSuccess
import ktor.Clients
import ktor.Ktor.downloadInstallerFromUrl
import ktor.Ktor.getRedirectedUrl
import ktor.Ktor.isRedirect
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import schemas.DefaultLocaleSchema
import schemas.InstallerSchema
import schemas.RemoteSchema
import schemas.SchemasImpl

object Url : KoinComponent {
    suspend fun Terminal.installerDownloadPrompt() {
        val installerManifestData: InstallerManifestData by inject()
        val schemasImpl: SchemasImpl by inject()
        do {
            println(brightGreen(installerUrlInfo))
            val input = prompt(brightWhite(PromptType.InstallerUrl.toString()))?.trim()
            val error = isUrlValid(url = input, schema = schemasImpl.installerSchema, canBeBlank = false).also {
                if (it == null) {
                    if (input != null) installerManifestData.installerUrl = input
                } else {
                    println(red(it))
                }
            }
            println()
        } while (error != null)

        val redirectedUrl = getRedirectedUrl(installerManifestData.installerUrl)
        if (
            redirectedUrl != installerManifestData.installerUrl &&
            redirectedUrl?.contains(other = "github", ignoreCase = true) != true
        ) {
            println(
                verticalLayout {
                    cell(brightYellow(redirectFound))
                    cell(cyan("Discovered URL: $redirectedUrl"))
                    cell(brightGreen(useDetectedUrl))
                    cell(brightWhite(useOriginalUrl))
                }
            )
            if (prompt(Prompts.enterChoice, default = "Y")?.trim()?.lowercase() != "N".lowercase()) {
                println(brightYellow(urlChanged))
                val error = isUrlValid(url = redirectedUrl, schema = schemasImpl.installerSchema, canBeBlank = false)
                if (error.isNullOrBlank() && !redirectedUrl.isNullOrBlank()) {
                    installerManifestData.installerUrl = redirectedUrl
                } else {
                    println(
                        verticalLayout {
                            cell(error)
                            cell("")
                            cell(brightYellow(detectedUrlValidationFailed))
                        }
                    )
                }
                println()
            } else {
                println(brightGreen("Original URL Retained - Proceeding with ${installerManifestData.installerUrl}"))
            }
        }

        if (installerManifestData.installers.map { it.installerUrl }.contains(installerManifestData.installerUrl)) {
            installerManifestData.installerSha256 = installerManifestData.installers.first {
                it.installerUrl == installerManifestData.installerUrl
            }.installerSha256
        } else {
            get<Clients>().httpClient.downloadInstallerFromUrl().apply {
                installerManifestData.installerSha256 = hash(Hashing.Algorithms.SHA256).uppercase()
                delete()
            }
        }
    }

    suspend fun Terminal.localeUrlPrompt(localeUrl: LocaleUrl) {
        val defaultLocaleSchema: DefaultLocaleSchema = get<SchemasImpl>().defaultLocaleSchema
        val defaultLocaleManifestData: DefaultLocaleManifestData by inject()
        do {
            println(brightYellow(publisherUrlInfo(localeUrl, defaultLocaleSchema.properties)))
            val input = prompt(
                prompt = brightWhite(localeUrl.toString()),
                default = getPreviousValue(localeUrl)?.also { println(gray("Previous $localeUrl: $it")) }
            )?.trim()
            val error = isUrlValid(url = input, schema = defaultLocaleSchema, canBeBlank = true).also {
                if (it.isNullOrBlank()) {
                    when (localeUrl) {
                        LocaleUrl.CopyrightUrl -> defaultLocaleManifestData.copyrightUrl = input
                        LocaleUrl.LicenseUrl -> defaultLocaleManifestData.licenseUrl = input
                        LocaleUrl.PackageUrl -> defaultLocaleManifestData.packageUrl = input
                        LocaleUrl.PublisherUrl -> defaultLocaleManifestData.publisherUrl = input
                        LocaleUrl.PublisherSupportUrl -> defaultLocaleManifestData.publisherSupportUrl = input
                        LocaleUrl.PublisherPrivacyUrl -> defaultLocaleManifestData.publisherPrivacyUrl = input
                        LocaleUrl.ReleaseNotesUrl -> defaultLocaleManifestData.releaseNotesUrl = input
                    }
                } else {
                    println(red(it))
                }
            }
            println()
        } while (!error.isNullOrBlank())
    }

    suspend fun isUrlValid(url: String?, schema: RemoteSchema, canBeBlank: Boolean): String? {
        val maxLength = when (schema) {
            is InstallerSchema -> schema.definitions.url.maxLength
            is DefaultLocaleSchema -> schema.definitions.url.maxLength
            else -> 0
        }
        val pattern = Regex(
            when (schema) {
                is InstallerSchema -> schema.definitions.url.pattern
                is DefaultLocaleSchema -> schema.definitions.url.pattern
                else -> ""
            }
        )
        return when {
            url.isNullOrBlank() && canBeBlank -> null
            url.isNullOrBlank() -> Errors.blankInput(PromptType.InstallerUrl)
            url.length > maxLength -> Errors.invalidLength(max = maxLength)
            !url.matches(pattern) -> Errors.invalidRegex(pattern)
            else -> {
                get<Clients>().httpClient.config { followRedirects = false }.use {
                    val installerUrlResponse = it.head(url)
                    if (!installerUrlResponse.status.isSuccess() && !installerUrlResponse.status.isRedirect()) {
                        Errors.unsuccessfulUrlResponse(installerUrlResponse)
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun getPreviousValue(localeUrl: LocaleUrl): String? {
        val remoteDefaultLocaleData = get<PreviousManifestData>().remoteDefaultLocaleData
        return when (localeUrl) {
            LocaleUrl.CopyrightUrl -> remoteDefaultLocaleData?.copyrightUrl
            LocaleUrl.LicenseUrl -> remoteDefaultLocaleData?.licenseUrl
            LocaleUrl.PackageUrl -> remoteDefaultLocaleData?.packageUrl
            LocaleUrl.PublisherUrl -> remoteDefaultLocaleData?.publisherUrl
            LocaleUrl.PublisherSupportUrl -> remoteDefaultLocaleData?.publisherSupportUrl
            LocaleUrl.PublisherPrivacyUrl -> remoteDefaultLocaleData?.privacyUrl
            LocaleUrl.ReleaseNotesUrl -> remoteDefaultLocaleData?.releaseNotesUrl
        }
    }

    private fun publisherUrlInfo(publisherUrl: LocaleUrl, schemaProperties: DefaultLocaleSchema.Properties): String {
        val description = when (publisherUrl) {
            LocaleUrl.CopyrightUrl -> schemaProperties.copyrightUrl.description
            LocaleUrl.LicenseUrl -> schemaProperties.licenseUrl.description
            LocaleUrl.PackageUrl -> schemaProperties.packageUrl.description
            LocaleUrl.PublisherUrl -> schemaProperties.publisherUrl.description
            LocaleUrl.PublisherSupportUrl -> schemaProperties.publisherSupportUrl.description
            LocaleUrl.PublisherPrivacyUrl -> schemaProperties.privacyUrl.description
            LocaleUrl.ReleaseNotesUrl -> schemaProperties.releaseNotesUrl.description
        }
        return "${Prompts.optional} Enter ${description.lowercase()}"
    }

    private const val installerUrlInfo = "${Prompts.required} Enter the download url to the installer"

    private const val redirectFound = "The URL appears to be redirected. " +
        "Would you like to use the destination URL instead?"

    private const val useDetectedUrl = "   [Y] Use detected URL"

    private const val detectedUrlValidationFailed = "Validation has failed for the detected URL. Using original URL."

    private const val useOriginalUrl = "   [N] Use original URL"

    private const val urlChanged = "[Warning] URL Changed - " +
        "The URL was changed during processing and will be re-validated"
}
