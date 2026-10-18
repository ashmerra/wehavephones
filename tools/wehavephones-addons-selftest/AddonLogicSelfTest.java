import com.wehavephones.mobile.addons.install.AddonInstaller;
import com.wehavephones.mobile.addons.install.DiscoveredAddon;
import com.wehavephones.mobile.addons.install.FileOps;
import com.wehavephones.mobile.addons.install.SafeZipExtractor;
import com.wehavephones.mobile.addons.install.ZipImporter;
import com.wehavephones.mobile.addons.install.ZipSecurityException;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.provider.GitHubAssetSelector;
import com.wehavephones.mobile.addons.provider.GitHubRepo;
import com.wehavephones.mobile.addons.provider.ProviderUrls;
import com.wehavephones.mobile.addons.repo.AddonIndex;
import com.wehavephones.mobile.addons.repo.Json;
import com.wehavephones.mobile.addons.required.ConsolePort;
import com.wehavephones.mobile.addons.required.ControllerSetupAddon;
import com.wehavephones.mobile.addons.scan.AddonScanner;
import com.wehavephones.mobile.addons.scan.TocParser;
import com.wehavephones.mobile.addons.scan.VersionComparator;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Host JVM self-test for the pure addon logic. Compiled and run by
 * scripts/test-wehavephones-addons.sh; exits non-zero when any check fails.
 */
public final class AddonLogicSelfTest {
    private static int passed = 0;
    private static int failed = 0;
    private static File workDir;

    public static void main(String[] args) throws Exception {
        workDir = Files.createTempDirectory("wehavephones-addons-selftest").toFile();
        try {
            testGameCatalog();
            testVersionComparator();
            testTocParsing();
            testProviderLabels();
            testIdentityPreservation();
            testExternalRewriteRebinds();
            testMultiTocSelection();
            testProviderUrls();
            testGitHubRepoAndAssets();
            testIndexRoundTrip();
            testSafeZipHappyPath();
            testSafeZipRejections();
            testZipImporterDiscovery();
            testInstallAndRollback();
            testHeldImportStaging();
            testDisabledUpdate();
            testEnableDisableAndBackup();
            testScannerGrouping();
            testConsolePort();
            testControllerSetupAddon();
        }
        finally {
            FileOps.deleteRecursively(workDir);
        }
        System.out.println("AddonLogicSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    // ------------------------------------------------------------------ tests

    private static void testGameCatalog() {
        checkEquals(WowFlavor.RETAIL, WowFlavor.detect("_retail_", "12.1.0.69933"), "retail by directory");
        checkEquals(WowFlavor.FOREVER_BETA, WowFlavor.detect("_classic_beta_", "1.60.1.69977"),
                "Forever beta in _classic_beta_ detected by version");
        checkEquals(WowFlavor.CLASSIC_BETA, WowFlavor.detect("_classic_beta_", "5.5.0.60000"),
                "_classic_beta_ with a MoP build stays Classic Beta");
        checkEquals(WowFlavor.FOREVER_BETA, WowFlavor.detect("_classic_beta_", ""),
                "_classic_beta_ without build info is the Forever beta");
        checkEquals(WowFlavor.FOREVER, WowFlavor.detect("_forever_", ""), "expected Forever directory");
        checkEquals(WowFlavor.FOREVER, WowFlavor.detect("_wow_forever_", "1.60.2.70001"),
                "unknown directory with a Forever build");
        checkEquals(WowFlavor.FOREVER_PTR, WowFlavor.detect("_forever_ptr_", "1.61.0.1"), "Forever PTR");
        checkEquals(WowFlavor.RETAIL_PTR, WowFlavor.detect("_new_ptr_", "12.2.0.1"),
                "unknown PTR directory classified by version");
        checkEquals(WowFlavor.ANNIVERSARY, WowFlavor.detect("_anniversary_", "3.4.5.1"),
                "known directory wins over a progressed version");
        checkEquals(WowFlavor.CLASSIC_ERA, WowFlavor.detect("_classic_era_", "1.15.8.1"), "era 1.15 is not Forever");
        checkEquals(WowFlavor.UNKNOWN, WowFlavor.detect("_mystery_", ""), "unknown without version");
        checkEquals(WowFlavor.FOREVER, WowFlavor.FOREVER_BETA.baseFlavor(), "Forever beta base flavor");
        checkEquals(WowFlavor.RETAIL, WowFlavor.RETAIL_XPTR.baseFlavor(), "XPTR base flavor");
        checkEquals(WowFlavor.CLASSIC_ERA, WowFlavor.CLASSIC_ERA_BETA.baseFlavor(), "era beta base flavor");
        checkEquals(16001, WowFlavor.interfaceFromVersion("1.60.1.69977"), "Forever interface");

        checkEquals("Wow-ARM64.exe", WowFlavor.pickExecutable(Arrays.asList("Wow.exe", "Wow-ARM64.exe", "WowVoiceProxy.exe")),
                "ARM64 retail exe preferred");
        checkEquals("WowB.exe", WowFlavor.pickExecutable(Arrays.asList("Battle.net.exe", "WowB.exe")), "beta exe");
        checkEquals("WowForever-arm64.exe", WowFlavor.pickExecutable(Arrays.asList("WowForever.exe", "WowForever-arm64.exe")),
                "unlisted exe found by pattern, ARM64 first");
        checkEquals("", WowFlavor.pickExecutable(Arrays.asList("WowVoiceProxy.exe", "Battle.net.exe")), "no game exe");
        check(!WowFlavor.isGameExecutable("WowVoiceProxy.exe"), "voice proxy is not the game");
        check(!WowFlavor.isGameExecutable("WowUp.exe"), "WowUp is not the game");
        check(WowFlavor.isGameExecutable("WowT.exe"), "test client is the game");
        check(WowFlavor.isGameExecutable("WOWCLASSIC-ARM64.EXE"), "exe match is case-insensitive");

        checkEquals("World of Warcraft", WowFlavor.gameTitle(WowFlavor.RETAIL, "_retail_"), "retail title");
        checkEquals("Midnight", WowFlavor.edition(WowFlavor.RETAIL, "12.1.0.69933"), "retail edition");
        checkEquals("Beta", WowFlavor.edition(WowFlavor.FOREVER_BETA, "1.60.1.69977"), "Forever beta edition");
        checkEquals("Mists of Pandaria \u00b7 PTR", WowFlavor.edition(WowFlavor.CLASSIC_PTR, "5.5.1.1"), "classic PTR edition");
        checkEquals("Classic Era Ptr", WowFlavor.prettyDirectory("_classic_era_ptr_"), "pretty directory");

        List<GitHubAssetSelector.Asset> assets = Arrays.asList(
                new GitHubAssetSelector.Asset("Foo-1.0-mainline.zip", "u1", "a1", 10, 1),
                new GitHubAssetSelector.Asset("Foo-1.0-forever.zip", "u2", "a2", 10, 1));
        GitHubAssetSelector.Asset forever = GitHubAssetSelector.select(assets, WowFlavor.FOREVER_BETA, false);
        check(forever != null && forever.name.contains("forever"), "Forever asset selected for the Forever beta");
    }

    private static void testVersionComparator() {
        check(VersionComparator.compare("1.10", "1.9") > 0, "1.10 newer than 1.9");
        check(VersionComparator.compare("12.1.0", "12.1.0") == 0, "exact versions equal");
        check(VersionComparator.compare("v12.1.11", "12.1.11") == 0, "v prefix ignored");
        check(VersionComparator.compare("2026.08.29", "2026.8.1") > 0, "date versions");
        check(VersionComparator.compare("5.5.4", "5.5.3") > 0, "patch bump");
        check(VersionComparator.compare("1.2.3a", "1.2.3") > 0, "letter suffix newer");
        check(VersionComparator.isUnknown(""), "empty is unknown");
        check(VersionComparator.isUnknown("@project-version@"), "placeholder is unknown");
        check(!VersionComparator.isNewer("", "1.0"), "unknown candidate is never newer");
        check(VersionComparator.isNewer("1.0", ""), "known candidate beats unknown installed");
        check(VersionComparator.compare("12.1.0.63206", "12.1.0") > 0, "build suffix compares newer");
    }

    private static void testTocParsing() {
        String text = "\uFEFF## Interface: 120100, 50504\n"
                + "## Title: WeakAuras\n"
                + "## Title-zhCN: \u5f31\u4f53\n"
                + "## Version: @project-version@\n"
                + "## Author: The WeakAuras Team\n"
                + "## Notes: Test addon\n"
                + "## X-Curse-Project-ID: 65387\n"
                + "## X-Wago-ID: VBNBxKx5\n"
                + "## X-WoWI-ID: 24910\n"
                + "## X-Tukui-ProjectID: 1234\n"
                + "## X-Tukui-ProjectFolders: ElvUI, ElvUI_Libraries\n"
                + "## Dependencies: Ace3, LibStub\n"
                + "## RequiredDeps: LibCompress\n"
                + "## OptionalDeps: BigWigs, DBM-Core\n"
                + "## LoadOnDemand: 1\n"
                + "## DefaultState: disabled\n"
                + "## AllowLoadGameType: mainline, cata\n";
        TocData toc = TocParser.parseText(text, "WeakAuras");
        checkEquals("WeakAuras", toc.baseName, "toc base name");
        checkEquals(2, toc.interfaces.size(), "comma interface list size");
        checkEquals("120100", toc.primaryInterface(), "primary interface");
        checkEquals("WeakAuras", toc.title, "title");
        checkEquals("Questie v12.1.0", TocParser.parseText("## Title: Questie|cFF00FF00 v12.1.0|r\n",
                "Questie").displayName(), "colour escapes stripped from title");
        checkEquals("Bag | Bank", TocData.plainText("|TInterface\\Icons\\INV_Misc_Bag_08:16|t Bag || |cnNORMAL_FONT_COLOR:Bank|r"),
                "texture, colour name and escaped bar");
        checkEquals("ElvUI", TocParser.parseText("## Title: |cff1784d1E|r|cffe5e3e3lvUI|r\n", "ElvUI").displayName(),
                "split colour runs joined");
        check(toc.hasPlaceholderVersion(), "placeholder version detected");
        checkEquals("", toc.versionOrUnknown(), "placeholder version is unknown");
        checkEquals("65387", toc.curseId, "curse id");
        checkEquals("VBNBxKx5", toc.wagoId, "wago id");
        checkEquals("24910", toc.wowiId, "wowi id");
        checkEquals("1234", toc.tukuiId, "tukui id");
        checkEquals(2, toc.tukuiFolders.size(), "tukui folders");
        checkEquals(Arrays.asList("Ace3", "LibStub", "LibCompress"), toc.dependencies,
                "required deps merged from aliases");
        checkEquals(Arrays.asList("BigWigs", "DBM-Core"), toc.optionalDeps, "optional deps");
        check(toc.loadOnDemand, "load on demand");
        checkEquals("disabled", toc.defaultState, "default state");
        checkEquals(2, toc.allowLoadGameType.size(), "allow load game type");

        TocData.ProviderBinding binding = toc.identity();
        check(binding != null && binding.provider == ProviderType.CURSEFORGE
                && binding.externalId.equals("65387"), "identity prefers curse id");

        checkEquals("1.2.3", TocParser.versionFromName("Addon-1.2.3"), "version from folder name");
        checkEquals("v2.0", TocParser.versionFromName("MyAddon_v2.0.zip"), "version from zip name");
    }

    private static void testProviderLabels() {
        // WoWInterface has no provider implementation, only the TOC identity.
        TocData toc = TocParser.parseText(
                "## X-WoWI-ID: 24910\n## X-Tukui-Project-ID: 1234\n", "Addon");
        TocData.ProviderBinding binding = toc.identity();
        check(binding != null && binding.provider == ProviderType.WOWINTERFACE
                && binding.externalId.equals("24910"), "identity maps WoWInterface to its own catalog");
        check("WoWInterface".equals(ProviderType.WOWINTERFACE.displayName), "WoWInterface label");
        check(!ProviderType.WOWINTERFACE.offered, "WoWInterface is display-only");

        // A detected catalog names itself even while updates stay off.
        AddonRecord curse = new AddonRecord();
        curse.providerId = ProviderType.CURSEFORGE.id;
        curse.externalId = "65387";
        check(curse.shownProvider() == ProviderType.CURSEFORGE, "bound CurseForge names itself");
        check(curse.hasProviderBinding() && !curse.hasOfferedBinding(), "CurseForge bound but not offered");
        check(curse.provider() == ProviderType.CURSEFORGE, "CurseForge identity preserved");

        AddonRecord wago = new AddonRecord();
        wago.providerId = ProviderType.WAGO.id;
        wago.externalId = "VBNBxKx5";
        check(wago.shownProvider() == ProviderType.WAGO, "bound Wago names itself");

        // Only a record with no binding reads as a Local ZIP.
        AddonRecord local = new AddonRecord();
        check(local.shownProvider() == ProviderType.LOCAL, "unbound addon stays Local ZIP");
        AddonRecord emptyId = new AddonRecord();
        emptyId.providerId = ProviderType.CURSEFORGE.id;
        check(emptyId.shownProvider() == ProviderType.LOCAL, "provider id without an id stays Local ZIP");
    }

    private static void testIdentityPreservation() throws Exception {
        // A manager-installed GitHub binding survives an external overwrite that
        // adds a TOC catalog id, so the app keeps tracking the source it installed.
        File addons = new File(workDir, "id-AddOns");
        write(new File(addons, "Questie/Questie.toc"),
                "## Version: 12.1.0\n## Title: Questie\n## X-Curse-Project-ID: 334372\n");
        AddonRecord previous = new AddonRecord();
        previous.flavorDir = "_classic_era_";
        previous.providerId = ProviderType.GITHUB.id;
        previous.externalId = "Questie/Questie";
        previous.folderNames.add("Questie");
        previous.latestVersion = "12.1.0";
        previous.status = InstallStatus.UP_TO_DATE.id;
        AddonRecord kept = AddonScanner.scan(addons, WowFlavor.CLASSIC_ERA, Arrays.asList(previous)).records.get(0);
        check(kept.provider() == ProviderType.GITHUB && "Questie/Questie".equals(kept.externalId),
                "GitHub binding survives an added TOC catalog id");

        // A folder with no previous record adopts the catalog its TOC names.
        File fresh = new File(workDir, "id-fresh/Interface/AddOns");
        write(new File(fresh, "Questie/Questie.toc"),
                "## Version: 12.1.0\n## Title: Questie\n## X-Curse-Project-ID: 334372\n");
        AddonRecord adopted = AddonScanner.scan(fresh, WowFlavor.CLASSIC_ERA,
                new ArrayList<AddonRecord>()).records.get(0);
        check(adopted.provider() == ProviderType.CURSEFORGE && "334372".equals(adopted.externalId),
                "a fresh folder adopts its TOC catalog id");

        // A folder with no id and no previous record stays local.
        File plain = new File(workDir, "id-plain/Interface/AddOns");
        write(new File(plain, "QuestieDB/QuestieDB_Vanilla.toc"),
                "## Version: 1.0.6\n## Title: QuestieDB\n");
        AddonRecord local = AddonScanner.scan(plain, WowFlavor.CLASSIC_ERA,
                new ArrayList<AddonRecord>()).records.get(0);
        check(local.provider() == ProviderType.LOCAL && local.shownProvider() == ProviderType.LOCAL,
                "a folder with no catalog id and no history stays Local ZIP");
    }

    private static void testExternalRewriteRebinds() throws Exception {
        // A folder written after we recorded its install was replaced by another
        // manager, so the catalog its TOC declares takes over. The stale GitHub
        // update metadata is dropped and the install time moves to the rewrite.
        File addons = new File(workDir, "rewrite-AddOns");
        write(new File(addons, "Questie/Questie.toc"),
                "## Version: 12.2.0\n## Title: Questie\n## X-Curse-Project-ID: 334372\n");
        AddonRecord previous = new AddonRecord();
        previous.flavorDir = "_classic_era_";
        previous.providerId = ProviderType.GITHUB.id;
        previous.externalId = "Questie/Questie";
        previous.folderNames.add("Questie");
        previous.latestVersion = "12.1.0";
        previous.latestReleaseId = "12.1.0";
        previous.installedFrom = "https://github.com/Questie/Questie/releases/download/12.1.0/Questie.zip";
        previous.status = InstallStatus.UP_TO_DATE.id;
        previous.installedAt = System.currentTimeMillis() - 3600_000L;

        AddonRecord rebound = AddonScanner.scan(addons, WowFlavor.CLASSIC_ERA, Arrays.asList(previous)).records.get(0);
        check(rebound.provider() == ProviderType.CURSEFORGE && "334372".equals(rebound.externalId),
                "an externally rewritten folder adopts its TOC catalog");
        check(rebound.latestVersion.isEmpty() && rebound.installedFrom.isEmpty(),
                "the replaced source's update metadata is dropped");
        check(rebound.installedAt > previous.installedAt, "the rewrite time replaces the install time");

        // The same folder with an install time at or after the rewrite is ours.
        File ours = new File(workDir, "rewrite-ours-AddOns");
        write(new File(ours, "Questie/Questie.toc"),
                "## Version: 12.2.0\n## Title: Questie\n## X-Curse-Project-ID: 334372\n");
        AddonRecord fresh = previous.copy();
        fresh.installedAt = System.currentTimeMillis();
        AddonRecord kept = AddonScanner.scan(ours, WowFlavor.CLASSIC_ERA, Arrays.asList(fresh)).records.get(0);
        check(kept.provider() == ProviderType.GITHUB, "a folder we just installed keeps its source");

        // Without a TOC catalog there is nothing to rebind to, so the source stands.
        File noId = new File(workDir, "rewrite-noid-AddOns");
        write(new File(noId, "Guidelime/Guidelime-Vanilla.toc"),
                "## Version: 6.009\n## Title: Guidelime\n");
        AddonRecord guide = new AddonRecord();
        guide.flavorDir = "_classic_era_";
        guide.providerId = ProviderType.GITHUB.id;
        guide.externalId = "max-ri/Guidelime";
        guide.folderNames.add("Guidelime");
        guide.installedAt = System.currentTimeMillis() - 3600_000L;
        AddonRecord guideKept = AddonScanner.scan(noId, WowFlavor.CLASSIC_ERA, Arrays.asList(guide)).records.get(0);
        check(guideKept.provider() == ProviderType.GITHUB, "a rewritten folder with no TOC catalog keeps its source");
    }

    private static void testMultiTocSelection() throws Exception {
        File folder = new File(workDir, "WeakAuras");
        write(new File(folder, "WeakAuras.toc"), "## Version: 1.0\n## Interface: 120100\n");
        write(new File(folder, "WeakAuras_Mainline.toc"), "## Version: 1.1\n## Interface: 120100\n");
        write(new File(folder, "WeakAuras_Cata.toc"), "## Version: 1.2\n## Interface: 40402\n");
        write(new File(folder, "WeakAurasOptions.toc"), "## Version: 9.9\n");
        write(new File(folder, "WeakAuras.lua"), "return 1\n");

        File retail = TocParser.chooseBestToc(folder, WowFlavor.RETAIL);
        check(retail != null && retail.getName().equals("WeakAuras_Mainline.toc"),
                "retail prefers _Mainline toc");

        File classic = TocParser.chooseBestToc(folder, WowFlavor.CLASSIC);
        check(classic != null && classic.getName().equals("WeakAuras.toc"),
                "mop falls back to base toc when no _Mists");

        File cata = TocParser.chooseBestToc(folder, WowFlavor.UNKNOWN);
        check(cata != null, "unknown flavor still finds a valid toc");

        check(!TocParser.baseMatchesFolder("WeakAurasOptions", "WeakAuras"),
                "sibling toc base does not match folder");
        check(TocParser.hasTocFor(folder), "hasTocFor sees the folder toc");
    }

    private static void testProviderUrls() {
        checkEquals("https://api.curseforge.com/v1/mods/search?gameId=1&gameVersionTypeId=517"
                        + "&searchFilter=weakauras&sortField=2&sortOrder=desc&index=0&pageSize=50",
                ProviderUrls.curseForgeSearch(517, "weakauras", 50), "curseforge search url");
        checkEquals("https://api.curseforge.com/v1/mods/65387/files?gameVersionTypeId=79434&index=0&pageSize=50",
                ProviderUrls.curseForgeFiles("65387", 79434, 50), "curseforge files url");
        checkEquals("https://api.curseforge.com/v1/mods/65387/files/1234/download-url",
                ProviderUrls.curseForgeDownloadUrl("65387", "1234"), "curseforge download url");
        checkEquals("https://api.tukui.org/v1/addon/elvui", ProviderUrls.tukuiAddon("elvui"),
                "tukui detail url");
        checkEquals("https://api.tukui.org/v1/changelog/elvui", ProviderUrls.tukuiChangelog("elvui"),
                "tukui changelog url");
        checkEquals("https://addons.wago.io/api/external/addons/_search?query=weak&game_version=retail"
                        + "&stability=stable",
                ProviderUrls.wagoSearch("weak", "retail"), "wago search url");
        checkEquals("https://addons.wago.io/api/external/addons/_recents",
                ProviderUrls.wagoBatchRecents(), "wago batch url");
        checkEquals("https://example.com/addon.zip?token=abc+123",
                ProviderUrls.withWagoToken("https://example.com/addon.zip", "abc 123"),
                "wago token query");
        checkEquals("https://api.github.com/repos/DeadlyBossMods/DeadlyBossMods/releases/latest",
                ProviderUrls.githubLatestRelease("DeadlyBossMods", "DeadlyBossMods"),
                "github latest release url");
        checkEquals("https://api.github.com/search/repositories?q=weakauras+wow+addon"
                        + "&sort=stars&order=desc&per_page=20",
                ProviderUrls.githubSearch("weakauras wow addon", 20), "github search url");
    }

    private static void testGitHubRepoAndAssets() {
        GitHubRepo repo = GitHubRepo.parse("DeadlyBossMods/DeadlyBossMods");
        check(repo != null && repo.key().equals("DeadlyBossMods/DeadlyBossMods"), "github repo parse");
        check(GitHubRepo.parse("https://github.com/tukui-org/ElvUI/") != null, "github url parse");
        check(GitHubRepo.parse("not a repo") == null, "invalid github id rejected");

        List<GitHubAssetSelector.Asset> assets = Arrays.asList(
                new GitHubAssetSelector.Asset("DBM-Core-12.1.11-classic.zip", "u1", "a1", 10, 1),
                new GitHubAssetSelector.Asset("DBM-Core-12.1.11-mainline.zip", "u2", "a2", 20, 1),
                new GitHubAssetSelector.Asset("DBM-Core-12.1.11-release.json", "u3", "a3", 30, 1),
                new GitHubAssetSelector.Asset("DBM-Core-12.1.11-mainline-nolib.zip", "u4", "a4", 40, 1));
        GitHubAssetSelector.Asset retail = GitHubAssetSelector.select(assets, WowFlavor.RETAIL, false);
        check(retail != null && retail.name.equals("DBM-Core-12.1.11-mainline.zip"),
                "mainline asset selected over classic");
        GitHubAssetSelector.Asset nolib = GitHubAssetSelector.select(assets, WowFlavor.RETAIL, true);
        check(nolib != null && nolib.name.contains("nolib"), "nolib preference honored");
        check(GitHubAssetSelector.score("Foo-1.0-classic.zip", WowFlavor.RETAIL, false) < 0,
                "explicit other-flavor asset rejected");
        check(GitHubAssetSelector.score("release.json", WowFlavor.RETAIL, false) < 0,
                "release.json is not installable");
        GitHubAssetSelector.Asset classic = GitHubAssetSelector.select(assets, WowFlavor.CLASSIC_ERA, false);
        check(classic != null && classic.name.contains("classic"), "classic era asset selected");
    }

    private static void testIndexRoundTrip() throws Exception {
        AddonIndex index = new AddonIndex();
        index.preferNolib = true;
        index.autoCheckOnOpen = false;
        AddonRecord record = new AddonRecord();
        record.id = "record-1";
        record.flavorDir = "_retail_";
        record.providerId = "curseforge";
        record.externalId = "65387";
        record.providerVersionId = "5123456";
        record.name = "WeakAuras";
        record.folderNames.add("WeakAuras");
        record.folderNames.add("WeakAurasOptions");
        record.toc.version = "5.20.2";
        record.toc.interfaces.add("120100");
        record.toc.curseId = "65387";
        record.latestVersion = "5.20.3";
        record.latestReleaseId = "5123999";
        record.latestReleaseDate = 1758800000000L;
        record.status = InstallStatus.UPDATE.id;
        record.enabled = false;
        index.addons.add(record);

        String json = index.toJsonString();
        AddonIndex parsed = AddonIndex.fromJsonString(json);
        checkEquals(1, parsed.addons.size(), "index round trip size");
        AddonRecord copy = parsed.addons.get(0);
        checkEquals("record-1", copy.id, "index id");
        checkEquals("_retail_", copy.flavorDir, "index flavor");
        checkEquals("curseforge", copy.providerId, "index provider");
        checkEquals("5123456", copy.providerVersionId, "index provider version");
        checkEquals(2, copy.folderNames.size(), "index folders");
        checkEquals("5.20.2", copy.toc.version, "index toc version");
        checkEquals("120100", copy.toc.primaryInterface(), "index interface");
        checkEquals("5.20.3", copy.latestVersion, "index latest version");
        check(parsed.preferNolib, "index settings prefer nolib");
        check(!parsed.autoCheckOnOpen, "index settings auto check");
        check(!copy.enabled, "index enabled flag");
        checkEquals(InstallStatus.UPDATE.id, copy.status, "index status");
        checkEquals(1, parsed.forFlavor("_retail_").size(), "index forFlavor");

        try {
            AddonIndex.fromJsonString("{ not json ");
            check(false, "corrupt json throws");
        }
        catch (Json.JsonException expected) {
            check(true, "corrupt json throws");
        }
    }

    private static void testSafeZipHappyPath() throws Exception {
        File zip = new File(workDir, "valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            addEntry(out, "ValidAddon/ValidAddon.toc",
                    "## Interface: 120100\n## Version: 1.2.3\n## Title: Valid Addon\n");
            addEntry(out, "ValidAddon/data.lua", "return 1\n");
        }
        File destination = new File(workDir, "extract-valid");
        SafeZipExtractor.Result result = SafeZipExtractor.extract(zip, destination, null);
        check(result.entries.size() == 2, "valid zip entry count");
        check(new File(destination, "ValidAddon/ValidAddon.toc").isFile(), "valid zip toc extracted");
        check(new File(destination, "ValidAddon/data.lua").isFile(), "valid zip data extracted");

        List<DiscoveredAddon> addons = ZipImporter.discoverAddons(destination, WowFlavor.RETAIL);
        checkEquals(1, addons.size(), "discovery finds one addon");
        checkEquals("ValidAddon", addons.get(0).folderName, "discovered folder name");
        checkEquals("1.2.3", addons.get(0).version(), "discovered version");
        checkEquals("Valid Addon", addons.get(0).displayName(), "discovered title");
    }

    private static void testSafeZipRejections() throws Exception {
        checkRejected("zip-slip", "../evil.txt", "evil", "traversal rejected");
        checkRejected("absolute", "/abs/evil.txt", "evil", "absolute path rejected");
        checkRejected("drive", "C:/Windows/evil.txt", "evil", "drive letter rejected");
        checkRejected("windows-sep", "..\\evil.txt", "evil", "backslash traversal rejected");
        checkRejected("invalid-char", "Bad:Name/file.txt", "x", "invalid windows char rejected");
        checkRejected("trailing-dot", "Bad./file.txt", "x", "trailing dot segment rejected");

        File duplicate = new File(workDir, "duplicate.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(duplicate))) {
            addEntry(out, "Foo/a.txt", "a");
            addEntry(out, "foo/A.TXT", "b");
        }
        check(extractThrows(duplicate), "case-insensitive duplicate rejected");

        File symlink = new File(workDir, "symlink.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(symlink)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("LinkAddon/link");
            entry.setUnixMode(0120777);
            out.putArchiveEntry(entry);
            out.write("../../etc/passwd".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        check(extractThrows(symlink), "symlink entry rejected");

        File bomb = new File(workDir, "bomb.zip");
        byte[] zeros = new byte[1024 * 1024];
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(bomb))) {
            addEntry(out, "Bomb/big.bin", zeros);
        }
        SafeZipExtractor.Limits limits = SafeZipExtractor.Limits.defaults();
        limits.ratioCheckThreshold = 1024;
        limits.maxEntryRatio = 10;
        limits.maxTotalRatio = 10;
        boolean rejected = false;
        try {
            SafeZipExtractor.extract(bomb, new File(workDir, "extract-bomb"), limits);
        }
        catch (ZipSecurityException expected) {
            rejected = true;
        }
        check(rejected, "compression ratio bomb rejected");
    }

    private static void testZipImporterDiscovery() throws Exception {
        File zip = new File(workDir, "wrapper.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            addEntry(out, "Addon-2.0.0/RealAddon/RealAddon.toc",
                    "## Interface: 120100\n## Title: Real Addon\n");
            addEntry(out, "Addon-2.0.0/RealAddon/core.lua", "return 1\n");
            addEntry(out, "Addon-2.0.0/README.txt", "docs\n");
        }
        File destination = new File(workDir, "extract-wrapper");
        SafeZipExtractor.extract(zip, destination, null);
        List<DiscoveredAddon> addons = ZipImporter.discoverAddons(destination, WowFlavor.RETAIL);
        checkEquals(1, addons.size(), "wrapper folder is traversed");
        checkEquals("RealAddon", addons.get(0).folderName, "real addon discovered");

        File interfaceZip = new File(workDir, "interface.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(interfaceZip))) {
            addEntry(out, "_retail_/Interface/AddOns/InnerAddon/InnerAddon.toc",
                    "## Interface: 120100\n## Version: 3.1\n");
        }
        File destination2 = new File(workDir, "extract-interface");
        SafeZipExtractor.extract(interfaceZip, destination2, null);
        List<DiscoveredAddon> inner = ZipImporter.discoverAddons(destination2, WowFlavor.RETAIL);
        checkEquals(1, inner.size(), "embedded Interface/AddOns traversed");
        checkEquals("3.1", inner.get(0).version(), "inner addon version");
    }

    private static void testInstallAndRollback() throws Exception {
        File addons = new File(workDir, "install-addons");
        File oldFolder = new File(addons, "OldName");
        write(new File(oldFolder, "OldName.toc"), "## Version: 1.0\n");
        write(new File(oldFolder, "OldName.lua"), "old\n");

        File staging = new File(workDir, "install-staging");
        File newFolder = new File(staging, "NewName");
        write(new File(newFolder, "NewName.toc"), "## Version: 2.0\n");
        write(new File(newFolder, "NewName.lua"), "new\n");
        List<DiscoveredAddon> discovered = ZipImporter.discoverAddons(staging, WowFlavor.RETAIL);
        checkEquals(1, discovered.size(), "install discovery");

        AddonInstaller.Result result = AddonInstaller.install(new AddonInstaller.Request(
                addons, staging, discovered, Arrays.asList("OldName"), false));
        check(!new File(addons, "OldName").exists(), "renamed old folder removed");
        check(new File(addons, "NewName/NewName.toc").isFile(), "new folder installed");
        check(result.backupDir.isDirectory(), "backup exists before commit");
        AddonInstaller.commit(result);
        check(!result.backupDir.exists(), "backup deleted after commit");
        check(!staging.exists(), "staging deleted after commit");

        File recovering = new File(workDir, "recover-addons");
        File crashBackup = new File(recovering, ".wehavephones-backup-abc");
        write(new File(crashBackup, "Restored/Restored.toc"), "## Version: 1.0\n");
        File staleStage = new File(recovering, ".wehavephones-staging-abc");
        write(new File(staleStage, "junk.txt"), "junk\n");
        AddonInstaller.recoverInterrupted(recovering);
        check(new File(recovering, "Restored/Restored.toc").isFile(), "crash backup restored");
        check(!staleStage.exists(), "stale staging deleted");
    }

    private static void testHeldImportStaging() throws Exception {
        File addons = new File(workDir, "held-addons");
        File pending = new File(addons, ".wehavephones-staging-pending");
        File pendingToc = new File(pending, "Pending/Pending.toc");
        File other = new File(addons, ".wehavephones-staging-old");
        write(pendingToc, "## Version: 1.0\n");
        write(new File(other, "junk.txt"), "junk\n");

        // Returning from the file picker rescans while the import waits for the player's choice.
        AddonInstaller.holdStaging(pending);
        AddonInstaller.recoverInterrupted(addons);
        check(pendingToc.isFile(), "held import staging survives a rescan");
        check(!other.exists(), "unheld staging still deleted");

        // The ConsolePort sync installs into the same AddOns folder in the background.
        ConsolePort.install(consolePortZip("cp-held.zip", "3.2.7", "ConsolePort_Bar"), addons, WowFlavor.RETAIL);
        checkEquals("3.2.7", ConsolePort.installedVersion(addons), "ConsolePort installs beside a held import");
        check(pendingToc.isFile(), "held import staging survives a ConsolePort install");

        // Confirming the import from the held staging installs it.
        List<DiscoveredAddon> found = ZipImporter.discoverAddons(pending, WowFlavor.RETAIL);
        checkEquals(1, found.size(), "held import discovery");
        AddonInstaller.Result result = AddonInstaller.install(new AddonInstaller.Request(
                addons, pending, found, null, false));
        AddonInstaller.commit(result);
        AddonInstaller.discardStaging(pending);
        check(new File(addons, "Pending/Pending.toc").isFile(), "held import installed");
        check(!pending.exists(), "confirmed import staging deleted");
        write(pendingToc, "## Version: 1.0\n");
        AddonInstaller.recoverInterrupted(addons);
        check(!pending.exists(), "confirmed import staging released");

        // A confirm that fails before installing (the game is running) still frees the preview.
        File failed = new File(addons, ".wehavephones-staging-failed");
        write(new File(failed, "Failed/Failed.toc"), "## Version: 1.0\n");
        AddonInstaller.holdStaging(failed);
        try {
            AddonInstaller.install(new AddonInstaller.Request(
                    addons, failed, new ArrayList<DiscoveredAddon>(), null, false));
            check(false, "empty import rejected");
        }
        catch (IOException expected) {
            check(true, "empty import rejected");
        }
        finally {
            AddonInstaller.discardStaging(failed);
        }
        check(!failed.exists(), "failed import staging deleted");
        write(new File(failed, "Failed/Failed.toc"), "## Version: 1.0\n");
        AddonInstaller.recoverInterrupted(addons);
        check(!failed.exists(), "failed import staging released");

        // A preview abandoned with its activity is released and the next rescan deletes it.
        File abandoned = new File(addons, ".wehavephones-staging-abandoned");
        write(new File(abandoned, "Gone/Gone.toc"), "## Version: 1.0\n");
        AddonInstaller.holdStaging(abandoned);
        AddonInstaller.recoverInterrupted(addons);
        check(abandoned.isDirectory(), "abandoned preview kept while held");
        AddonInstaller.releaseStaging(abandoned);
        AddonInstaller.recoverInterrupted(addons);
        check(!abandoned.exists(), "released preview deleted by the next rescan");
        check(new File(addons, "Pending/Pending.toc").isFile(), "recovery leaves installed addons alone");
    }

    private static void testDisabledUpdate() throws Exception {
        File addons = new File(workDir, "disabled-update-addons");
        write(new File(addons, "DAddon.disabled/DAddon.toc"), "## Version: 1.0\n");

        File staging = new File(workDir, "disabled-update-staging");
        write(new File(staging, "DAddon/DAddon.toc"), "## Version: 2.0\n");
        List<DiscoveredAddon> discovered = ZipImporter.discoverAddons(staging, WowFlavor.RETAIL);

        AddonInstaller.Result result = AddonInstaller.install(new AddonInstaller.Request(
                addons, staging, discovered, Arrays.asList("DAddon"), true));
        check(new File(addons, "DAddon.disabled/DAddon.toc").isFile(),
                "disabled addon update stays disabled");
        check(!new File(addons, "DAddon/DAddon.toc").exists(),
                "no active folder after disabled update");
        AddonInstaller.commit(result);
        check(!result.backupDir.exists(), "disabled update backup committed");
    }

    private static void testEnableDisableAndBackup() throws Exception {
        File addons = new File(workDir, "toggle-addons");
        write(new File(addons, "ToggleAddon/ToggleAddon.toc"), "## Version: 1.0\n");

        AddonInstaller.setEnabled(addons, Arrays.asList("ToggleAddon"), false);
        check(new File(addons, "ToggleAddon.disabled").isDirectory(), "disable renames folder");
        check(!new File(addons, "ToggleAddon").exists(), "active folder gone after disable");

        AddonInstaller.setEnabled(addons, Arrays.asList("ToggleAddon"), true);
        check(new File(addons, "ToggleAddon").isDirectory(), "enable restores folder");
        check(!new File(addons, "ToggleAddon.disabled").exists(), "disabled folder gone after enable");

        // The manager toggles a record using the physical folder names a scan
        // reports, so a disabled addon arrives as "ToggleAddon.disabled".
        AddonInstaller.setEnabled(addons, Arrays.asList("ToggleAddon"), false);
        AddonScanner.ScanResult disabledScan = AddonScanner.scan(addons, WowFlavor.RETAIL, null);
        checkEquals(1, disabledScan.records.size(), "one record after disable");
        AddonRecord disabledRecord = disabledScan.records.get(0);
        checkEquals("ToggleAddon.disabled", disabledRecord.folderNames.get(0),
                "scan reports the physical disabled folder name");
        AddonInstaller.setEnabled(addons, disabledRecord.folderNames, true);
        check(new File(addons, "ToggleAddon").isDirectory(), "enable restores scanned disabled folder");
        check(!new File(addons, "ToggleAddon.disabled").exists(),
                "scanned disabled folder gone after enable");

        File backup = AddonInstaller.removeToBackup(addons, Arrays.asList("ToggleAddon"));
        check(!new File(addons, "ToggleAddon").exists(), "delete moves folder away");
        check(FileOps.isDeletedDir(backup.getName()), "delete backup directory created");
        check(backup.isDirectory() || !backup.exists(), "delete backup retained or empty");
    }

    private static void testScannerGrouping() throws Exception {
        File addons = new File(workDir, "scan-addons");
        write(new File(addons, "WA/WA.toc"),
                "## Version: 5.0\n## Title: WeakAuras\n## X-Curse-Project-ID: 55\n");
        write(new File(addons, "WA_Options/WA_Options.toc"),
                "## Version: 5.0\n## Title: WeakAuras\n## X-Curse-Project-ID: 55\n");
        write(new File(addons, "Solo.disabled/Solo.toc"), "## Version: 9.9\n## Title: Solo\n");
        write(new File(addons, "Junk/readme.txt"), "no toc here\n");

        AddonRecord previous = new AddonRecord();
        previous.id = "wa-record";
        previous.flavorDir = "_retail_";
        previous.providerId = "curseforge";
        previous.externalId = "55";
        previous.name = "WeakAuras";
        previous.folderNames.add("WA");
        previous.latestVersion = "6.0";
        previous.status = InstallStatus.UPDATE.id;

        AddonScanner.ScanResult result = AddonScanner.scan(addons, WowFlavor.RETAIL,
                Arrays.asList(previous));
        AddonRecord grouped = null;
        AddonRecord solo = null;
        AddonRecord junk = null;
        for (AddonRecord record : result.records) {
            if ("55".equals(record.externalId)) grouped = record;
            if ("Solo".equals(record.name)) solo = record;
            if ("Junk".equals(record.name)) junk = record;
        }
        check(grouped != null, "grouped record found by TOC identity");
        check(grouped != null && grouped.folderNames.size() == 2, "multi-folder addon grouped");
        check(grouped != null && "wa-record".equals(grouped.id), "index identity preserved");
        check(grouped != null && InstallStatus.UPDATE.id.equals(grouped.status),
                "update status recomputed from preserved latest");
        check(solo != null && !solo.enabled && InstallStatus.DISABLED.id.equals(solo.status),
                "disabled folder detected");
        check(junk != null && junk.unrecognized, "unrecognized folder reported");

        // A release tag the comparator cannot order keeps the update check's verdict.
        File tagged = new File(workDir, "scan-tagged/Interface/AddOns");
        write(new File(tagged, "Questie/Questie.toc"), "## Version: 12.1.0\n## Title: Questie\n");
        AddonRecord checked = new AddonRecord();
        checked.flavorDir = "_retail_";
        checked.providerId = ProviderType.GITHUB.id;
        checked.externalId = "Questie/Questie";
        checked.folderNames.add("Questie");
        checked.toc.version = "12.1.0";
        checked.latestVersion = "bundle/v12.1.0+v1.0.6";
        checked.status = InstallStatus.UP_TO_DATE.id;
        AddonRecord kept = AddonScanner.scan(tagged, WowFlavor.RETAIL, Arrays.asList(checked)).records.get(0);
        checkEquals(InstallStatus.UP_TO_DATE.id, kept.status, "unordered tag keeps the checked status");
        write(new File(tagged, "Questie/Questie.toc"), "## Version: 12.2.0\n## Title: Questie\n");
        AddonRecord changed = AddonScanner.scan(tagged, WowFlavor.RETAIL, Arrays.asList(checked)).records.get(0);
        checkEquals(InstallStatus.UNKNOWN.id, changed.status, "a changed install drops the old verdict");
    }

    private static void testConsolePort() throws Exception {
        check(ConsolePort.requiredFor(WowFlavor.RETAIL), "ConsolePort required on retail");
        check(ConsolePort.requiredFor(WowFlavor.CLASSIC_ERA_PTR), "ConsolePort required on era PTR");
        check(ConsolePort.requiredFor(WowFlavor.UNKNOWN), "ConsolePort required on unknown games");
        check(!ConsolePort.requiredFor(WowFlavor.FOREVER), "Forever uses native controller support");
        check(!ConsolePort.requiredFor(WowFlavor.detect("_classic_beta_", "1.60.1.60000")),
                "Forever beta in _classic_beta_ skipped by version");
        check(!ConsolePort.requiredFor(WowFlavor.detect("_classic_beta_", "wow_classic_beta", "")),
                "offered Forever beta (no build yet) uses native controller support");
        check(ConsolePort.isConsolePortFolder("ConsolePort_Bar.disabled"), "module folder recognized");
        check(!ConsolePort.isConsolePortFolder("ConsolePortish"), "similar names are not ConsolePort");
        check(ConsolePort.needsInstall("", "3.2.7"), "missing ConsolePort needs install");
        check(ConsolePort.needsInstall("3.2.6", "3.2.7"), "older ConsolePort needs update");
        check(!ConsolePort.needsInstall("3.2.8", "3.2.7"), "never downgrades");
        check(!ConsolePort.needsInstall("3.2.7", "3.2.7"), "same version is current");

        File addons = new File(workDir, "cp-AddOns");
        write(new File(addons, "Other/Other.toc"), "## Interface: 120100\n## Version: 1.0\n");
        File v1 = consolePortZip("cp-3.2.6.zip", "3.2.6", "ConsolePort_Bar", "ConsolePort_Old");
        ConsolePort.install(v1, addons, WowFlavor.RETAIL);
        checkEquals("3.2.6", ConsolePort.installedVersion(addons), "ConsolePort fresh install");
        check(new File(addons, "ConsolePort_Old").isDirectory(), "fresh install has every module");

        File v2 = consolePortZip("cp-3.2.7.zip", "3.2.7", "ConsolePort_Bar");
        List<String> folders = ConsolePort.install(v2, addons, WowFlavor.RETAIL);
        checkEquals("3.2.7", ConsolePort.installedVersion(addons), "ConsolePort update");
        check(!new File(addons, "ConsolePort_Old").exists(), "dropped module removed on update");
        check(new File(addons, "Other/Other.toc").isFile(), "other addons untouched");
        checkEquals(Arrays.asList("ConsolePort", "ConsolePort_Bar"), sorted(folders), "installed suite folders");
        check(FileOps.listDirectoryNames(addons).size() == 3, "no staging/backup left behind");

        AddonScanner.ScanResult scan = AddonScanner.scan(addons, WowFlavor.RETAIL, new ArrayList<AddonRecord>());
        AddonRecord suite = null;
        for (AddonRecord record : scan.records) {
            if (record.folderNames.contains("ConsolePort")) suite = record;
        }
        check(suite != null && suite.folderNames.size() == 2, "scanner groups the ConsolePort suite");
        check(suite != null && suite.provider() == ProviderType.GITHUB
                && ConsolePort.GITHUB_REPO.equals(suite.externalId), "ConsolePort bound to GitHub, not CurseForge");
        AddonRecord stale = new AddonRecord();
        stale.flavorDir = "_retail_";
        stale.providerId = ProviderType.CURSEFORGE.id;
        stale.externalId = ConsolePort.CURSEFORGE_ID;
        stale.folderNames.add("ConsolePort");
        stale.latestVersion = "3.0";
        AddonScanner.ScanResult rescan = AddonScanner.scan(addons, WowFlavor.RETAIL, Arrays.asList(stale));
        AddonRecord rebound = null;
        for (AddonRecord record : rescan.records) {
            if (record.folderNames.contains("ConsolePort")) rebound = record;
        }
        check(rebound != null && rebound.provider() == ProviderType.GITHUB
                && rebound.id.equals(stale.id), "stale CurseForge binding rebound to GitHub");
        check(ConsolePort.isConsolePort(rebound), "record recognized as ConsolePort");

        AddonInstaller.setEnabled(addons, ConsolePort.installedFolders(addons), false);
        check(ConsolePort.isDisabled(addons), "suite disabled");
        checkEquals("3.2.7", ConsolePort.installedVersion(addons), "disabled suite version readable");
        ConsolePort.install(consolePortZip("cp-3.2.8.zip", "3.2.8", "ConsolePort_Bar"), addons, WowFlavor.RETAIL);
        check(ConsolePort.isDisabled(addons) && new File(addons, "ConsolePort_Bar.disabled").isDirectory(),
                "update keeps a disabled suite disabled");
        checkEquals("3.2.8", ConsolePort.installedVersion(addons), "disabled suite updated");

        File notConsolePort = new File(workDir, "not-cp.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(notConsolePort))) {
            addEntry(out, "ConsolePort_Bar/ConsolePort_Bar.toc", "## Interface: 120100\n");
        }
        boolean rejected = false;
        try {
            ConsolePort.install(notConsolePort, new File(workDir, "cp-empty-AddOns"), WowFlavor.RETAIL);
        }
        catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "archive without the core addon rejected");

        // The real bundled release, when scripts/fetch-consoleport.sh has run.
        File bundled = new File(System.getProperty("wehavephones.consoleport.zip", ""));
        String bundledVersion = System.getProperty("wehavephones.consoleport.version", "");
        if (bundled.isFile() && !bundledVersion.isEmpty()) {
            File real = new File(workDir, "cp-real-AddOns");
            List<String> realFolders = ConsolePort.install(bundled, real, WowFlavor.CLASSIC);
            checkEquals(bundledVersion, ConsolePort.installedVersion(real), "bundled ConsolePort installs");
            check(realFolders.size() >= 5, "bundled ConsolePort installs its modules (" + realFolders + ")");
        }
        else {
            System.out.println("  (bundled ConsolePort archive not present; real-archive check skipped)");
        }
    }

    private static void testControllerSetupAddon() throws Exception {
        check(ControllerSetupAddon.isFolder("WeHavePhonesControllerSetup.disabled"), "companion folder recognized");
        check(!ConsolePort.isConsolePortFolder(ControllerSetupAddon.FOLDER),
                "companion is not a ConsolePort_ module (suite updates would remove it)");

        File addons = new File(workDir, "setup-AddOns");
        File consolePort = new File(addons, "ConsolePort/ConsolePort.toc");
        write(consolePort, "## Interface: 120100, 11510\n## Version: 3.2.8\n");
        java.util.Map<String, byte[]> bundled = new java.util.LinkedHashMap<>();
        bundled.put(ControllerSetupAddon.TOC,
                "## Interface: 11509\n## Version: 1.0.0\n## Dependencies: ConsolePort\n\nSetup.lua\n"
                        .getBytes(StandardCharsets.UTF_8));
        bundled.put("Setup.lua", "print(1)\n".getBytes(StandardCharsets.UTF_8));

        check(ControllerSetupAddon.sync(addons, bundled), "companion installed");
        String toc = new String(Files.readAllBytes(new File(addons, "WeHavePhonesControllerSetup/WeHavePhonesControllerSetup.toc")
                .toPath()), StandardCharsets.UTF_8);
        check(toc.startsWith("## Interface: 120100, 11510\n") && toc.contains("## Dependencies: ConsolePort"),
                "companion TOC follows ConsolePort's interface line");
        check(!ControllerSetupAddon.sync(addons, bundled), "unchanged companion not rewritten");

        write(consolePort, "## Interface: 120100, 11511\n## Version: 3.2.9\n");
        check(ControllerSetupAddon.sync(addons, bundled), "ConsolePort interface bump refreshes the companion");
        bundled.put("Setup.lua", "print(2)\n".getBytes(StandardCharsets.UTF_8));
        check(ControllerSetupAddon.sync(addons, bundled), "new bundled script replaces the old one");
        check(FileOps.listDirectoryNames(addons).size() == 2, "no temp files left behind");

        FileOps.move(new File(addons, ControllerSetupAddon.FOLDER), new File(addons, ControllerSetupAddon.FOLDER + ".disabled"));
        bundled.put("Setup.lua", "print(3)\n".getBytes(StandardCharsets.UTF_8));
        check(!ControllerSetupAddon.sync(addons, bundled)
                && !new File(addons, ControllerSetupAddon.FOLDER).exists(), "disabled companion left alone");

        // The shipped asset: WoW only loads files the TOC lists, so an empty TOC silently disables the setup.
        File assetDir = new File(System.getProperty("wehavephones.controllersetup.dir", ""));
        if (assetDir.isDirectory()) {
            String assetToc = new String(Files.readAllBytes(new File(assetDir, ControllerSetupAddon.TOC).toPath()),
                    StandardCharsets.UTF_8);
            check(assetToc.contains("## Interface:") && assetToc.contains("## Dependencies: ConsolePort")
                    && assetToc.contains("## SavedVariables: WeHavePhonesControllerSetupDB")
                    && assetToc.contains("\nSetup.lua"), "bundled companion TOC loads Setup.lua after ConsolePort");
        }
        else {
            System.out.println("  (companion asset folder not given; bundled TOC check skipped)");
        }
    }

    private static File consolePortZip(String name, String version, String... modules) throws IOException {
        File zip = new File(workDir, name);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            addEntry(out, "ConsolePort/ConsolePort.toc", "## Interface: 120100, 50504, 11509\n## Title: Console Port\n"
                    + "## Version: " + version + "\n## X-Curse-Project-ID: 91376\n## X-Wago-ID: LvNAYjNo\n");
            addEntry(out, "ConsolePort/API.lua", "return 1\n");
            for (String module : modules) {
                addEntry(out, module + "/" + module + ".toc", "## Interface: 120100\n## Version: " + version
                        + "\n## X-Curse-Project-ID: 91376\n");
            }
        }
        return zip;
    }

    private static List<String> sorted(List<String> values) {
        ArrayList<String> copy = new ArrayList<>(values);
        java.util.Collections.sort(copy);
        return copy;
    }

    // ------------------------------------------------------------------ helpers

    private static void checkRejected(String name, String entryName, String content, String label)
            throws Exception {
        File zip = new File(workDir, name + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            addEntry(out, entryName, content);
        }
        check(extractThrows(zip), label);
    }

    private static boolean extractThrows(File zip) {
        try {
            SafeZipExtractor.extract(zip, new File(workDir, "extract-" + zip.getName()), null);
            return false;
        }
        catch (ZipSecurityException expected) {
            return true;
        }
        catch (Exception other) {
            System.out.println("  (unexpected " + other.getClass().getSimpleName() + ": "
                    + other.getMessage() + ")");
            return false;
        }
    }

    private static void addEntry(ZipOutputStream out, String name, String content) throws IOException {
        addEntry(out, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void addEntry(ZipOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void check(boolean condition, String label) {
        if (condition) {
            passed++;
        }
        else {
            failed++;
            System.out.println("FAIL: " + label);
        }
    }

    private static void checkEquals(Object expected, Object actual, String label) {
        boolean equal = expected == null ? actual == null : expected.equals(actual);
        if (!equal) {
            failed++;
            System.out.println("FAIL: " + label + " (expected <" + expected + "> but was <" + actual + ">)");
        }
        else {
            passed++;
        }
    }
}
