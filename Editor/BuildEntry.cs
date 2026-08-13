#if UNITY_EDITOR

using System;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.iOS.Xcode;
using UnityEngine;

namespace Pearz.CI
{
public static class BuildEntry
{
    private const string LogPrefix = "[Pearz.CI]";
    private const string IosDeviceBuildMarkerFileName = ".pearz-ci-ios-device-build";

    /// <summary>
    /// Jenkins entry point:
    /// -executeMethod Pearz.CI.BuildEntry.BuildAndroid
    /// </summary>
    public static void BuildAndroid()
    {
        // Giữ ngoài try để khối catch còn ghi được metadata thất bại,
        // kể cả khi lỗi xảy ra trước lúc Unity bắt đầu build.
        BuildConfiguration configuration = null;
        BuildReport         report        = null;

        try
        {
            Log("========================================");
            Log("Starting Android build");
            Log($"Unity version: {Application.unityVersion}");
            Log($"Project path: {GetProjectPath()}");
            Log("========================================");

            string[] scenes = GetEnabledScenes();

            if (scenes.Length == 0)
            {
                throw new InvalidOperationException(
                    "Không có scene nào được bật trong Build Profiles / Build Settings.");
            }

            foreach (string scene in scenes)
            {
                Log($"Scene: {scene}");
            }

            configuration = ReadConfiguration();

            PrintConfiguration(configuration);
            PrepareOutputDirectory(configuration.OutputPath);
            ApplyAndroidSettings(configuration);
            ConfigureKeystore(configuration);

            BuildPlayerOptions buildPlayerOptions = new BuildPlayerOptions
            {
                scenes           = scenes,
                locationPathName = configuration.OutputPath,
                target           = BuildTarget.Android,
                targetGroup      = BuildTargetGroup.Android,
                options          = GetBuildOptions(configuration)
            };

            Log("Calling BuildPipeline.BuildPlayer...");

            report = BuildPipeline.BuildPlayer(buildPlayerOptions);
            BuildSummary summary = report.summary;

            PrintBuildSummary(summary);

            if (summary.result != BuildResult.Succeeded)
            {
                throw new Exception(
                    $"Android build failed. Result: {summary.result}, " +
                    $"Errors: {summary.totalErrors}");
            }

            if (!File.Exists(configuration.OutputPath))
            {
                throw new FileNotFoundException(
                    "Unity báo build thành công nhưng không tìm thấy output.",
                    configuration.OutputPath);
            }

            TryWriteBuildMetadata(report, configuration, string.Empty);

            Log("========================================");
            Log("ANDROID BUILD SUCCEEDED");
            Log($"Output: {configuration.OutputPath}");
            Log($"Size: {FormatBytes(new FileInfo(configuration.OutputPath).Length)}");
            Log("========================================");

            ExitBatchMode(0);
        }
        catch (Exception exception)
        {
            Debug.LogException(exception);
            Error("========================================");
            Error("ANDROID BUILD FAILED");
            Error(exception.Message);
            Error("========================================");

            // Phải ghi trước EditorApplication.Exit: lệnh này kết thúc
            // tiến trình ngay, nên finally không chắc chạy. Không có file
            // này thì Jenkins không biết lý do build hỏng.
            TryWriteBuildMetadata(report, configuration, exception.Message);

            if (Application.isBatchMode)
            {
                EditorApplication.Exit(1);
                return;
            }

            throw;
        }
    }

    /// <summary>
    /// Jenkins entry point for a macOS agent:
    /// -executeMethod Pearz.CI.BuildEntry.BuildIOS
    /// Exports an Xcode project; Jenkins signs and packages the IPA with Xcode.
    /// </summary>
    public static void BuildIOS()
    {
        IosBuildConfiguration configuration = null;

        try
        {
            Log("Starting iOS Xcode project export");
            string[] scenes = GetEnabledScenes();

            if (scenes.Length == 0)
                throw new InvalidOperationException(
                    "Không có scene nào được bật trong Build Profiles / Build Settings.");

            configuration = ReadIosConfiguration();
            PrepareIosOutputDirectory(configuration.OutputPath);
            ApplyIosSettings(configuration);

            BuildOptions options = BuildOptions.None;
            if (configuration.UnityDevelopmentBuild)
                options |= BuildOptions.Development;
            if (configuration.ScriptDebugging)
            {
                if (!configuration.UnityDevelopmentBuild)
                    throw new InvalidOperationException(
                        "SCRIPT_DEBUGGING=true yêu cầu UNITY_DEVELOPMENT_BUILD=true.");
                options |= BuildOptions.AllowDebugging;
            }

            BuildReport report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = configuration.OutputPath,
                target = BuildTarget.iOS,
                targetGroup = BuildTargetGroup.iOS,
                options = options
            });

            PrintBuildSummary(report.summary);

            if (report.summary.result != BuildResult.Succeeded ||
                !Directory.Exists(configuration.OutputPath))
            {
                throw new Exception(
                    $"iOS Xcode export failed. Result: {report.summary.result}, " +
                    $"Errors: {report.summary.totalErrors}");
            }

            RemoveIapCapabilityForPersonalTeamDeviceBuild(configuration.OutputPath);
            Log("IOS XCODE EXPORT SUCCEEDED");
            Log($"Output: {configuration.OutputPath}");
            ExitBatchMode(0);
        }
        catch (Exception exception)
        {
            Debug.LogException(exception);
            Error("IOS XCODE EXPORT FAILED");
            Error(exception.Message);
            if (Application.isBatchMode)
            {
                EditorApplication.Exit(1);
                return;
            }
            throw;
        }
    }

    /// <summary>
    /// Jenkins entry point for a Windows standalone player:
    /// -executeMethod Pearz.CI.BuildEntry.BuildWindows
    /// </summary>
    public static void BuildWindows()
    {
        WindowsBuildConfiguration configuration = null;
        BuildReport report = null;

        try
        {
            Log("Starting Windows standalone build");
            string[] scenes = GetEnabledScenes();
            if (scenes.Length == 0)
                throw new InvalidOperationException(
                    "Không có scene nào được bật trong Build Profiles / Build Settings.");

            configuration = ReadWindowsConfiguration();
            PrepareWindowsOutputDirectory(configuration.OutputPath);
            ApplyWindowsSettings(configuration);

            report = BuildPipeline.BuildPlayer(new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = configuration.OutputPath,
                target = BuildTarget.StandaloneWindows64,
                targetGroup = BuildTargetGroup.Standalone,
                options = GetBuildOptions(configuration.UnityDevelopmentBuild,
                    configuration.ScriptDebugging)
            });

            PrintBuildSummary(report.summary);
            if (report.summary.result != BuildResult.Succeeded ||
                !File.Exists(configuration.OutputPath))
            {
                throw new Exception(
                    $"Windows build failed. Result: {report.summary.result}, " +
                    $"Errors: {report.summary.totalErrors}");
            }

            TryWriteWindowsBuildMetadata(report, configuration, string.Empty);
            Log("WINDOWS BUILD SUCCEEDED");
            Log($"Output: {configuration.OutputPath}");
            ExitBatchMode(0);
        }
        catch (Exception exception)
        {
            Debug.LogException(exception);
            Error("WINDOWS BUILD FAILED");
            Error(exception.Message);
            TryWriteWindowsBuildMetadata(report, configuration, exception.Message);
            if (Application.isBatchMode)
            {
                EditorApplication.Exit(1);
                return;
            }
            throw;
        }
    }

    /// <summary>
    /// Unity IAP adds the In-App Purchase Xcode capability during export.
    /// Apple Personal Team profiles cannot sign that capability. For the
    /// Jenkins device-test path only, remove it after the Unity export.
    /// </summary>
    private static void RemoveIapCapabilityForPersonalTeamDeviceBuild(string outputPath)
    {
        string markerPath = Path.Combine(GetProjectPath(), IosDeviceBuildMarkerFileName);
        if (!File.Exists(markerPath))
            return;

        string projectPath = PBXProject.GetPBXProjectPath(outputPath);
        if (!File.Exists(projectPath))
            throw new FileNotFoundException("Exported Xcode project file was not found.", projectPath);

        const string capabilityPattern =
            @"\s*com\.apple\.InAppPurchase\s*=\s*\{\s*enabled\s*=\s*1;\s*\};";
        string contents = File.ReadAllText(projectPath);
        string updatedContents = Regex.Replace(contents, capabilityPattern, string.Empty);

        if (contents == updatedContents)
        {
            Log("iOS device export: In-App Purchase capability was not present in the Xcode project.");
            return;
        }

        File.WriteAllText(projectPath, updatedContents);
        Log("iOS device export: removed In-App Purchase capability for Personal Team signing.");
    }

    private static BuildConfiguration ReadConfiguration()
    {
        string projectPath = GetProjectPath();

        string defaultOutputPath = Path.Combine(
            projectPath,
            "Builds",
            "Android",
            "app.apk");

        string outputPath = Path.GetFullPath(
            GetEnvironmentVariable("OUTPUT_PATH", defaultOutputPath));

        // Ưu tiên tên mới. BUILD_TYPE được giữ để tương thích Jenkins job cũ.
        string buildConfiguration = GetEnvironmentVariable("BUILD_CONFIGURATION");

        if (string.IsNullOrWhiteSpace(buildConfiguration))
        {
            buildConfiguration = GetEnvironmentVariable("BUILD_TYPE", "release");
        }

        buildConfiguration = buildConfiguration.Trim().ToLowerInvariant();

        bool developmentBuild =
            buildConfiguration == "develop" ||
            buildConfiguration == "development" ||
            buildConfiguration == "debug";

        bool releaseBuild =
            buildConfiguration == "release" ||
            buildConfiguration == "production" ||
            buildConfiguration == "prod";

        if (!developmentBuild && !releaseBuild)
        {
            throw new ArgumentException(
                $"BUILD_CONFIGURATION không hợp lệ: '{buildConfiguration}'. " +
                "Giá trị hợp lệ: develop hoặc release.");
        }

        bool buildAppBundle = GetBooleanEnvironmentVariable(
            "BUILD_APP_BUNDLE",
            false);

        outputPath = Path.ChangeExtension(
            outputPath,
            buildAppBundle ? ".aab" : ".apk");

        return new BuildConfiguration
        {
            OutputPath        = outputPath,
            ConfigurationName = developmentBuild ? "develop" : "release",
            DevelopmentBuild  = developmentBuild,
            UnityDevelopmentBuild =
                GetBooleanEnvironmentVariable(
                    "UNITY_DEVELOPMENT_BUILD",
                    false),
            ScriptDebugging =
                GetBooleanEnvironmentVariable("SCRIPT_DEBUGGING", false),
            BuildAppBundle = buildAppBundle,

            TargetArchitectures =
                GetEnvironmentVariable("TARGET_ARCHITECTURES"),
            Il2CppCodeGeneration =
                GetEnvironmentVariable("IL2CPP_CODE_GENERATION"),
            ManagedStrippingLevel =
                GetEnvironmentVariable("MANAGED_STRIPPING_LEVEL"),
            StripEngineCode =
                GetNullableBooleanEnvironmentVariable("STRIP_ENGINE_CODE"),
            MinifyRelease =
                GetNullableBooleanEnvironmentVariable("MINIFY_RELEASE"),

            ProductName      = GetEnvironmentVariable("PRODUCT_NAME"),
            BundleIdentifier = GetEnvironmentVariable("BUNDLE_IDENTIFIER"),
            ScriptingDefineSymbols =
                GetEnvironmentVariable("SCRIPTING_DEFINE_SYMBOLS"),

            AppVersion = GetEnvironmentVariable(
                "APP_VERSION",
                PlayerSettings.bundleVersion),

            AndroidVersionCode = GetIntegerEnvironmentVariable(
                "ANDROID_VERSION_CODE",
                PlayerSettings.Android.bundleVersionCode),

            KeystorePath     = GetEnvironmentVariable("KEYSTORE_PATH"),
            KeystorePassword = GetEnvironmentVariable("KEYSTORE_PASSWORD"),
            KeyAliasName     = GetEnvironmentVariable("KEY_ALIAS_NAME"),
            KeyAliasPassword = GetEnvironmentVariable("KEY_ALIAS_PASSWORD")
        };
    }

    private static IosBuildConfiguration ReadIosConfiguration()
    {
        string outputPath = Path.GetFullPath(GetEnvironmentVariable(
            "OUTPUT_PATH",
            Path.Combine(GetProjectPath(), "Builds", "iOS", "Unity-iPhone")));

        return new IosBuildConfiguration
        {
            OutputPath = outputPath,
            UnityDevelopmentBuild = GetBooleanEnvironmentVariable(
                "UNITY_DEVELOPMENT_BUILD", false),
            ScriptDebugging = GetBooleanEnvironmentVariable(
                "SCRIPT_DEBUGGING", false),
            ProductName = GetEnvironmentVariable("PRODUCT_NAME"),
            BundleIdentifier = GetEnvironmentVariable("BUNDLE_IDENTIFIER"),
            ScriptingDefineSymbols = GetEnvironmentVariable("SCRIPTING_DEFINE_SYMBOLS"),
            AppVersion = GetEnvironmentVariable("APP_VERSION", PlayerSettings.bundleVersion),
            BuildNumber = GetEnvironmentVariable(
                "IOS_BUILD_NUMBER", PlayerSettings.iOS.buildNumber),
            Il2CppCodeGeneration = GetEnvironmentVariable("IL2CPP_CODE_GENERATION"),
            ManagedStrippingLevel = GetEnvironmentVariable("MANAGED_STRIPPING_LEVEL"),
            StripEngineCode = GetNullableBooleanEnvironmentVariable("STRIP_ENGINE_CODE")
        };
    }

    private static WindowsBuildConfiguration ReadWindowsConfiguration()
    {
        string outputPath = Path.GetFullPath(GetEnvironmentVariable(
            "OUTPUT_PATH",
            Path.Combine(GetProjectPath(), "Builds", "Windows", "Game.exe")));

        return new WindowsBuildConfiguration
        {
            OutputPath = Path.ChangeExtension(outputPath, ".exe"),
            UnityDevelopmentBuild = GetBooleanEnvironmentVariable(
                "UNITY_DEVELOPMENT_BUILD", false),
            ScriptDebugging = GetBooleanEnvironmentVariable("SCRIPT_DEBUGGING", false),
            ProductName = GetEnvironmentVariable("PRODUCT_NAME"),
            ScriptingDefineSymbols = GetEnvironmentVariable("SCRIPTING_DEFINE_SYMBOLS"),
            AppVersion = GetEnvironmentVariable("APP_VERSION", PlayerSettings.bundleVersion),
            Il2CppCodeGeneration = GetEnvironmentVariable("IL2CPP_CODE_GENERATION"),
            ManagedStrippingLevel = GetEnvironmentVariable("MANAGED_STRIPPING_LEVEL"),
            StripEngineCode = GetNullableBooleanEnvironmentVariable("STRIP_ENGINE_CODE")
        };
    }

    private static void PrepareWindowsOutputDirectory(string outputPath)
    {
        PrepareOutputDirectory(outputPath);
        string dataDirectory = Path.Combine(
            Path.GetDirectoryName(outputPath) ?? string.Empty,
            Path.GetFileNameWithoutExtension(outputPath) + "_Data");
        if (Directory.Exists(dataDirectory))
            FileUtil.DeleteFileOrDirectory(dataDirectory);
    }

    private static void ApplyWindowsSettings(WindowsBuildConfiguration configuration)
    {
        if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.StandaloneWindows64 &&
            !EditorUserBuildSettings.SwitchActiveBuildTarget(
                BuildTargetGroup.Standalone, BuildTarget.StandaloneWindows64))
            throw new Exception("Không thể chuyển active build target sang Windows 64-bit.");

        if (!string.IsNullOrWhiteSpace(configuration.ProductName))
            PlayerSettings.productName = configuration.ProductName;
        if (!string.IsNullOrWhiteSpace(configuration.ScriptingDefineSymbols))
            SetWindowsScriptingDefineSymbols(configuration.ScriptingDefineSymbols);
        if (!string.IsNullOrWhiteSpace(configuration.AppVersion))
            PlayerSettings.bundleVersion = configuration.AppVersion;
        if (!string.IsNullOrWhiteSpace(configuration.Il2CppCodeGeneration))
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.Standalone,
                ParseIl2CppCodeGeneration(configuration.Il2CppCodeGeneration));
        if (!string.IsNullOrWhiteSpace(configuration.ManagedStrippingLevel))
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.Standalone,
                ParseManagedStrippingLevel(configuration.ManagedStrippingLevel));
        if (configuration.StripEngineCode.HasValue)
            PlayerSettings.stripEngineCode = configuration.StripEngineCode.Value;
    }

    private static void PrepareIosOutputDirectory(string outputPath)
    {
        if (Directory.Exists(outputPath))
            FileUtil.DeleteFileOrDirectory(outputPath);
        Directory.CreateDirectory(outputPath);
    }

    private static void ApplyIosSettings(IosBuildConfiguration configuration)
    {
        if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.iOS &&
            !EditorUserBuildSettings.SwitchActiveBuildTarget(
                BuildTargetGroup.iOS, BuildTarget.iOS))
        {
            throw new Exception("Không thể chuyển active build target sang iOS.");
        }
        if (!string.IsNullOrWhiteSpace(configuration.ProductName))
            PlayerSettings.productName = configuration.ProductName;
        if (!string.IsNullOrWhiteSpace(configuration.BundleIdentifier))
            SetIosApplicationIdentifier(configuration.BundleIdentifier);
        if (!string.IsNullOrWhiteSpace(configuration.ScriptingDefineSymbols))
            SetIosScriptingDefineSymbols(configuration.ScriptingDefineSymbols);
        if (!string.IsNullOrWhiteSpace(configuration.AppVersion))
            PlayerSettings.bundleVersion = configuration.AppVersion;
        if (!string.IsNullOrWhiteSpace(configuration.BuildNumber))
            PlayerSettings.iOS.buildNumber = configuration.BuildNumber;
        if (!string.IsNullOrWhiteSpace(configuration.Il2CppCodeGeneration))
            PlayerSettings.SetIl2CppCodeGeneration(NamedBuildTarget.iOS,
                ParseIl2CppCodeGeneration(configuration.Il2CppCodeGeneration));
        if (!string.IsNullOrWhiteSpace(configuration.ManagedStrippingLevel))
            PlayerSettings.SetManagedStrippingLevel(NamedBuildTarget.iOS,
                ParseManagedStrippingLevel(configuration.ManagedStrippingLevel));
        if (configuration.StripEngineCode.HasValue)
            PlayerSettings.stripEngineCode = configuration.StripEngineCode.Value;
    }

    private static void ApplyAndroidSettings(BuildConfiguration configuration)
    {
        Log("Applying Android build settings...");

        if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
        {
            Log("Switching active build target to Android...");

            bool switched = EditorUserBuildSettings.SwitchActiveBuildTarget(
                BuildTargetGroup.Android,
                BuildTarget.Android);

            if (!switched)
            {
                throw new Exception(
                    "Không thể chuyển active build target sang Android.");
            }
        }

        EditorUserBuildSettings.buildAppBundle =
            configuration.BuildAppBundle;

        ApplySizeOptimizationSettings(configuration);

        // Giữ Project Settings hiện tại nếu Jenkins không truyền giá trị.
        if (!string.IsNullOrWhiteSpace(configuration.ProductName))
        {
            PlayerSettings.productName = configuration.ProductName;
        }

        if (!string.IsNullOrWhiteSpace(configuration.BundleIdentifier))
        {
            SetAndroidApplicationIdentifier(
                configuration.BundleIdentifier);
        }

        if (!string.IsNullOrWhiteSpace(
                configuration.ScriptingDefineSymbols))
        {
            SetAndroidScriptingDefineSymbols(
                configuration.ScriptingDefineSymbols);
        }

        if (!string.IsNullOrWhiteSpace(configuration.AppVersion))
        {
            PlayerSettings.bundleVersion = configuration.AppVersion;
        }

        if (configuration.AndroidVersionCode <= 0)
        {
            throw new ArgumentOutOfRangeException(
                nameof(configuration.AndroidVersionCode),
                "ANDROID_VERSION_CODE phải lớn hơn 0.");
        }

        PlayerSettings.Android.bundleVersionCode =
            configuration.AndroidVersionCode;

        Log($"Build configuration: {configuration.ConfigurationName}");
        Log($"Product name: {PlayerSettings.productName}");
        Log($"Application identifier: {GetAndroidApplicationIdentifier()}");
        Log($"Bundle version: {PlayerSettings.bundleVersion}");
        Log($"Android version code: {PlayerSettings.Android.bundleVersionCode}");
        Log($"Build App Bundle: {EditorUserBuildSettings.buildAppBundle}");
        Log($"Target architectures: {PlayerSettings.Android.targetArchitectures}");

        Log(
            "IL2CPP code generation: " +
            PlayerSettings.GetIl2CppCodeGeneration(NamedBuildTarget.Android));

        Log(
            "Managed stripping level: " +
            PlayerSettings.GetManagedStrippingLevel(NamedBuildTarget.Android));

        Log($"Strip engine code: {PlayerSettings.stripEngineCode}");
        Log($"Minify release: {PlayerSettings.Android.minifyRelease}");
        Log($"Scripting define symbols: {GetAndroidScriptingDefineSymbols()}");
    }

    private static void ApplySizeOptimizationSettings(
        BuildConfiguration configuration)
    {
        if (!string.IsNullOrWhiteSpace(configuration.TargetArchitectures))
        {
            PlayerSettings.Android.targetArchitectures =
                ParseTargetArchitectures(configuration.TargetArchitectures);
        }

        if (!string.IsNullOrWhiteSpace(configuration.Il2CppCodeGeneration))
        {
            PlayerSettings.SetIl2CppCodeGeneration(
                NamedBuildTarget.Android,
                ParseIl2CppCodeGeneration(
                    configuration.Il2CppCodeGeneration));
        }

        if (!string.IsNullOrWhiteSpace(configuration.ManagedStrippingLevel))
        {
            PlayerSettings.SetManagedStrippingLevel(
                NamedBuildTarget.Android,
                ParseManagedStrippingLevel(
                    configuration.ManagedStrippingLevel));
        }

        if (configuration.StripEngineCode.HasValue)
        {
            PlayerSettings.stripEngineCode =
                configuration.StripEngineCode.Value;
        }

        if (configuration.MinifyRelease.HasValue)
        {
            PlayerSettings.Android.minifyRelease =
                configuration.MinifyRelease.Value;
        }
    }

    private static AndroidArchitecture ParseTargetArchitectures(string value)
    {
        string normalized = NormalizeChoice(value);

        switch (normalized)
        {
            case "ARM64":
                return AndroidArchitecture.ARM64;

            case "ARMV7ARM64":
            case "ARM64ARMV7":
            case "BOTH":
                return AndroidArchitecture.ARMv7 |
                       AndroidArchitecture.ARM64;

            default:
                throw new ArgumentException(
                    $"TARGET_ARCHITECTURES không hợp lệ: '{value}'. " +
                    "Giá trị hợp lệ: ARM64 hoặc ARMV7_ARM64.");
        }
    }

    private static Il2CppCodeGeneration ParseIl2CppCodeGeneration(
        string value)
    {
        switch (NormalizeChoice(value))
        {
            case "OPTIMIZESIZE":
            case "SIZE":
                return Il2CppCodeGeneration.OptimizeSize;

            case "OPTIMIZESPEED":
            case "SPEED":
                return Il2CppCodeGeneration.OptimizeSpeed;

            default:
                throw new ArgumentException(
                    $"IL2CPP_CODE_GENERATION không hợp lệ: '{value}'. " +
                    "Giá trị hợp lệ: OptimizeSize hoặc OptimizeSpeed.");
        }
    }

    private static ManagedStrippingLevel ParseManagedStrippingLevel(
        string value)
    {
        switch (NormalizeChoice(value))
        {
            case "LOW":
                return ManagedStrippingLevel.Low;

            case "MEDIUM":
                return ManagedStrippingLevel.Medium;

            case "HIGH":
                return ManagedStrippingLevel.High;

            default:
                throw new ArgumentException(
                    $"MANAGED_STRIPPING_LEVEL không hợp lệ: '{value}'. " +
                    "Giá trị hợp lệ: Low, Medium hoặc High.");
        }
    }

    private static string NormalizeChoice(string value)
    {
        return new string(
            value
                .Where(char.IsLetterOrDigit)
                .Select(char.ToUpperInvariant)
                .ToArray());
    }

    private static BuildOptions GetBuildOptions(
        BuildConfiguration configuration)
    {
        return GetBuildOptions(
            configuration.UnityDevelopmentBuild,
            configuration.ScriptDebugging);
    }

    private static BuildOptions GetBuildOptions(
        bool unityDevelopmentBuild,
        bool scriptDebugging)
    {
        if (scriptDebugging && !unityDevelopmentBuild)
        {
            throw new InvalidOperationException(
                "SCRIPT_DEBUGGING=true yêu cầu " +
                "UNITY_DEVELOPMENT_BUILD=true.");
        }

        if (unityDevelopmentBuild)
        {
            Log("Unity Development Build: enabled");

            BuildOptions options = BuildOptions.Development;

            if (scriptDebugging)
            {
                Log("Script Debugging: enabled");
                options |= BuildOptions.AllowDebugging;
            }
            else
            {
                Log("Script Debugging: disabled");
            }

            return options;
        }

        Log("Unity Development Build: disabled");
        Log("Script Debugging: disabled");

        // Không ép LZ4/LZ4HC để release dùng compression trong Build Profile,
        // giống thao tác Build trực tiếp trong Unity.
        return BuildOptions.None;
    }

    private static void ConfigureKeystore(
        BuildConfiguration configuration)
    {
        bool hasAnyKeystoreValue =
            !string.IsNullOrWhiteSpace(configuration.KeystorePath) ||
            !string.IsNullOrWhiteSpace(configuration.KeystorePassword) ||
            !string.IsNullOrWhiteSpace(configuration.KeyAliasName) ||
            !string.IsNullOrWhiteSpace(configuration.KeyAliasPassword);

        if (!hasAnyKeystoreValue)
        {
            Log("No keystore environment variables provided.");
            Log("Using current Player Settings signing configuration.");

            return;
        }

        string configuredKeystorePath = configuration.KeystorePath;

        if (string.IsNullOrWhiteSpace(configuredKeystorePath))
        {
            string bundleIdentifier = GetAndroidApplicationIdentifier();

            if (string.IsNullOrWhiteSpace(bundleIdentifier))
            {
                throw new Exception(
                    "Không thể xác định Android package name để tìm keystore.");
            }

            configuredKeystorePath = Path.Combine(
                GetProjectPath(),
                "Config",
                bundleIdentifier + ".keystore");

            Log(
                "KEYSTORE_PATH not provided. " +
                "Using the project keystore convention.");
        }
        else if (!Path.IsPathRooted(configuredKeystorePath))
        {
            configuredKeystorePath = Path.Combine(
                GetProjectPath(),
                configuredKeystorePath);
        }

        string keystorePath = Path.GetFullPath(configuredKeystorePath);

        if (!File.Exists(keystorePath))
        {
            throw new FileNotFoundException(
                "Không tìm thấy Android keystore.",
                keystorePath);
        }

        if (string.IsNullOrWhiteSpace(configuration.KeystorePassword))
            throw new Exception("Thiếu biến KEYSTORE_PASSWORD.");

        if (string.IsNullOrWhiteSpace(configuration.KeyAliasName))
            throw new Exception("Thiếu biến KEY_ALIAS_NAME.");

        if (string.IsNullOrWhiteSpace(configuration.KeyAliasPassword))
            throw new Exception("Thiếu biến KEY_ALIAS_PASSWORD.");

        PlayerSettings.Android.useCustomKeystore = true;
        PlayerSettings.Android.keystoreName      = keystorePath;

        PlayerSettings.Android.keystorePass =
            configuration.KeystorePassword;

        PlayerSettings.Android.keyaliasName =
            configuration.KeyAliasName;

        PlayerSettings.Android.keyaliasPass =
            configuration.KeyAliasPassword;

        Log($"Keystore path: {PlayerSettings.Android.keystoreName}");
        Log($"Key alias: {PlayerSettings.Android.keyaliasName}");
    }

    private static string[] GetEnabledScenes()
    {
        return EditorBuildSettings.scenes
            .Where(scene => scene.enabled)
            .Select(scene => scene.path)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .ToArray();
    }

    private static void PrepareOutputDirectory(string outputPath)
    {
        string directory = Path.GetDirectoryName(outputPath);

        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new InvalidOperationException(
                $"Output path không hợp lệ: {outputPath}");
        }

        Directory.CreateDirectory(directory);

        if (File.Exists(outputPath))
        {
            Log($"Deleting old output: {outputPath}");
            File.Delete(outputPath);
        }
    }

    private static void PrintConfiguration(
        BuildConfiguration configuration)
    {
        Log("Build configuration:");
        Log($"Output: {configuration.OutputPath}");
        Log($"Configuration: {configuration.ConfigurationName}");
        Log($"Development configuration: {configuration.DevelopmentBuild}");
        Log($"Unity Development Build: {configuration.UnityDevelopmentBuild}");
        Log($"Script debugging: {configuration.ScriptDebugging}");
        Log($"App version: {configuration.AppVersion}");
        Log($"Version code: {configuration.AndroidVersionCode}");
        Log($"App Bundle: {configuration.BuildAppBundle}");

        Log(
            "Target architectures override: " +
            DisplayOverride(configuration.TargetArchitectures));

        Log(
            "IL2CPP code generation override: " +
            DisplayOverride(configuration.Il2CppCodeGeneration));

        Log(
            "Managed stripping level override: " +
            DisplayOverride(configuration.ManagedStrippingLevel));

        Log(
            "Strip engine code override: " +
            DisplayOverride(configuration.StripEngineCode));

        Log(
            "Minify release override: " +
            DisplayOverride(configuration.MinifyRelease));

        Log($"Product override: {DisplayOverride(configuration.ProductName)}");
        Log($"Bundle ID override: {DisplayOverride(configuration.BundleIdentifier)}");

        Log(
            "Define symbols override: " +
            DisplayOverride(configuration.ScriptingDefineSymbols));
    }

    private static void PrintBuildSummary(BuildSummary summary)
    {
        Log("========================================");
        Log("Unity build summary");
        Log($"Result: {summary.result}");
        Log($"Platform: {summary.platform}");
        Log($"Output: {summary.outputPath}");
        Log($"Duration: {summary.totalTime}");
        Log($"Size: {FormatBytes((long)summary.totalSize)}");
        Log($"Warnings: {summary.totalWarnings}");
        Log($"Errors: {summary.totalErrors}");
        Log("========================================");
    }

    /// <summary>
    /// Ghi build-metadata.json cho cả build thành công lẫn thất bại.
    /// <paramref name="report"/> và <paramref name="configuration"/> có thể
    /// null khi lỗi xảy ra trước lúc Unity kịp build.
    /// </summary>
    private static void TryWriteBuildMetadata(
        BuildReport report,
        BuildConfiguration configuration,
        string errorMessage)
    {
        try
        {
            BuildSummary? summary = null;

            if (report != null)
            {
                summary = report.summary;
            }

            FileInfo outputFile = null;

            if (configuration != null &&
                !string.IsNullOrWhiteSpace(configuration.OutputPath))
            {
                outputFile = new FileInfo(configuration.OutputPath);
            }

            string outputDirectory = outputFile != null
                ? outputFile.DirectoryName
                : Path.Combine(GetProjectPath(), "Builds", "Android");

            if (string.IsNullOrWhiteSpace(outputDirectory))
            {
                Warning("Cannot determine the build metadata directory.");
                return;
            }

            Directory.CreateDirectory(outputDirectory);

            bool outputExists =
                outputFile != null && outputFile.Exists;

            string mappingPath = report != null
                ? TryCopyMappingFile(report, outputDirectory)
                : string.Empty;

            BuildMetadata metadata = new BuildMetadata
            {
                schemaVersion = 2,
                result = summary.HasValue
                    ? summary.Value.result.ToString()
                    : BuildResult.Failed.ToString(),
                errorMessage = errorMessage ?? string.Empty,
                productName = PlayerSettings.productName,
                bundleIdentifier = GetAndroidApplicationIdentifier(),
                versionName = PlayerSettings.bundleVersion,
                androidVersionCode =
                    PlayerSettings.Android.bundleVersionCode,
                unityVersion = Application.unityVersion,
                scriptingBackend = GetAndroidScriptingBackend(),
                managedStrippingLevel =
                    PlayerSettings
                        .GetManagedStrippingLevel(NamedBuildTarget.Android)
                        .ToString(),
                orientation =
                    PlayerSettings.defaultInterfaceOrientation.ToString(),
                targetArchitectures =
                    PlayerSettings.Android.targetArchitectures.ToString(),
                scriptingDefineSymbols =
                    SplitScriptingDefineSymbols(
                        GetAndroidScriptingDefineSymbols()),
                buildConfiguration = configuration != null
                    ? configuration.ConfigurationName
                    : string.Empty,
                buildAppBundle =
                    configuration != null && configuration.BuildAppBundle,
                outputFileName = outputFile != null
                    ? outputFile.Name
                    : string.Empty,
                outputSizeBytes = outputExists ? outputFile.Length : 0,
                buildDurationSeconds = summary.HasValue
                    ? summary.Value.totalTime.TotalSeconds
                    : 0d,
                warningCount = summary.HasValue
                    ? (int)summary.Value.totalWarnings
                    : 0,
                errorCount = summary.HasValue
                    ? (int)summary.Value.totalErrors
                    : 0,
                mappingFileName = string.IsNullOrWhiteSpace(mappingPath)
                    ? string.Empty
                    : Path.GetFileName(mappingPath),
                mappingSizeBytes = string.IsNullOrWhiteSpace(mappingPath)
                    ? 0
                    : new FileInfo(mappingPath).Length,
                generatedAtUtc = DateTime.UtcNow.ToString("o")
            };

            string metadataPath = Path.Combine(
                outputDirectory,
                "build-metadata.json");

            File.WriteAllText(
                metadataPath,
                JsonUtility.ToJson(metadata, true));

            Log($"Build metadata: {metadataPath}");
        }
        catch (Exception exception)
        {
            Warning(
                "Could not write optional build metadata: " +
                exception.Message);
        }
    }

    private static void TryWriteWindowsBuildMetadata(
        BuildReport report,
        WindowsBuildConfiguration configuration,
        string errorMessage)
    {
        try
        {
            string outputPath = configuration != null
                ? configuration.OutputPath
                : Path.Combine(GetProjectPath(), "Builds", "Windows", "Game.exe");
            string outputDirectory = Path.GetDirectoryName(outputPath);
            Directory.CreateDirectory(outputDirectory);
            BuildSummary? summary = report != null ? report.summary : null;
            FileInfo outputFile = new FileInfo(outputPath);

            WindowsBuildMetadata metadata = new WindowsBuildMetadata
            {
                schemaVersion = 1,
                result = summary.HasValue ? summary.Value.result.ToString() : BuildResult.Failed.ToString(),
                errorMessage = errorMessage ?? string.Empty,
                productName = PlayerSettings.productName,
                versionName = PlayerSettings.bundleVersion,
                unityVersion = Application.unityVersion,
                scriptingBackend = PlayerSettings.GetScriptingBackend(NamedBuildTarget.Standalone).ToString(),
                managedStrippingLevel = PlayerSettings.GetManagedStrippingLevel(NamedBuildTarget.Standalone).ToString(),
                scriptingDefineSymbols = SplitScriptingDefineSymbols(GetWindowsScriptingDefineSymbols()),
                outputFileName = outputFile.Name,
                outputSizeBytes = outputFile.Exists ? outputFile.Length : 0,
                buildDurationSeconds = summary.HasValue ? summary.Value.totalTime.TotalSeconds : 0d,
                warningCount = summary.HasValue ? (int)summary.Value.totalWarnings : 0,
                errorCount = summary.HasValue ? (int)summary.Value.totalErrors : 0,
                generatedAtUtc = DateTime.UtcNow.ToString("o")
            };
            File.WriteAllText(Path.Combine(outputDirectory, "build-metadata.json"),
                JsonUtility.ToJson(metadata, true));
        }
        catch (Exception exception)
        {
            Warning("Could not write optional Windows build metadata: " + exception.Message);
        }
    }

    private static string TryCopyMappingFile(
        BuildReport report,
        string outputDirectory)
    {
        try
        {
            string sourcePath = report.GetFiles()
                .Select(file => file.path)
                .Where(path => !string.IsNullOrWhiteSpace(path))
                .FirstOrDefault(path =>
                    string.Equals(
                        Path.GetFileName(path),
                        "mapping.txt",
                        StringComparison.OrdinalIgnoreCase) &&
                    File.Exists(path));

            if (string.IsNullOrWhiteSpace(sourcePath))
            {
                return string.Empty;
            }

            string destinationPath = Path.Combine(
                outputDirectory,
                "mapping.txt");

            if (!string.Equals(
                    Path.GetFullPath(sourcePath),
                    Path.GetFullPath(destinationPath),
                    StringComparison.OrdinalIgnoreCase))
            {
                File.Copy(sourcePath, destinationPath, true);
            }

            Log($"Mapping file: {destinationPath}");
            return destinationPath;
        }
        catch (Exception exception)
        {
            Warning(
                "Could not collect optional mapping.txt: " +
                exception.Message);
            return string.Empty;
        }
    }

    private static string GetAndroidScriptingBackend()
    {
        ScriptingImplementation backend =
            PlayerSettings.GetScriptingBackend(NamedBuildTarget.Android);

        return backend == ScriptingImplementation.IL2CPP
            ? "IL2CPP"
            : "Mono";
    }

    private static string[] SplitScriptingDefineSymbols(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return Array.Empty<string>();
        }

        return value
            .Split(new[] { ';' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(symbol => symbol.Trim())
            .Where(symbol => !string.IsNullOrWhiteSpace(symbol))
            .Distinct()
            .ToArray();
    }

    private static string GetProjectPath()
    {
        return Path.GetFullPath(
            Path.Combine(Application.dataPath, ".."));
    }

    private static string GetEnvironmentVariable(
        string name,
        string defaultValue = "")
    {
        string value = Environment.GetEnvironmentVariable(name);

        return string.IsNullOrWhiteSpace(value)
            ? defaultValue
            : value.Trim();
    }

    private static int GetIntegerEnvironmentVariable(
        string name,
        int defaultValue)
    {
        string value = Environment.GetEnvironmentVariable(name);

        if (string.IsNullOrWhiteSpace(value))
            return defaultValue;

        if (!int.TryParse(value, out int result))
        {
            throw new FormatException(
                $"{name} phải là số nguyên. Giá trị hiện tại: {value}");
        }

        return result;
    }

    private static bool GetBooleanEnvironmentVariable(
        string name,
        bool defaultValue)
    {
        string value = Environment.GetEnvironmentVariable(name);

        if (string.IsNullOrWhiteSpace(value))
            return defaultValue;

        if (value.Equals("true", StringComparison.OrdinalIgnoreCase) ||
            value.Equals("1", StringComparison.OrdinalIgnoreCase) ||
            value.Equals("yes", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        if (value.Equals("false", StringComparison.OrdinalIgnoreCase) ||
            value.Equals("0", StringComparison.OrdinalIgnoreCase) ||
            value.Equals("no", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        throw new FormatException(
            $"{name} phải là true/false, 1/0 hoặc yes/no. " +
            $"Giá trị hiện tại: {value}");
    }

    private static bool? GetNullableBooleanEnvironmentVariable(string name)
    {
        string value = Environment.GetEnvironmentVariable(name);

        if (string.IsNullOrWhiteSpace(value))
            return null;

        return GetBooleanEnvironmentVariable(name, false);
    }

    private static string DisplayOverride(string value)
    {
        return string.IsNullOrWhiteSpace(value)
            ? "(keep Project Settings)"
            : value;
    }

    private static string DisplayOverride(bool? value)
    {
        return value.HasValue
            ? value.Value.ToString()
            : "(keep Project Settings)";
    }

    private static string FormatBytes(long bytes)
    {
        string[] units     = { "B", "KB", "MB", "GB", "TB" };
        double   size      = bytes;
        int      unitIndex = 0;

        while (size >= 1024 && unitIndex < units.Length - 1)
        {
            size /= 1024;
            unitIndex++;
        }

        return $"{size:0.##} {units[unitIndex]}";
    }

    private static void ExitBatchMode(int exitCode)
    {
        if (Application.isBatchMode)
        {
            EditorApplication.Exit(exitCode);
        }
    }

    private static void SetAndroidApplicationIdentifier(string value)
    {
#if UNITY_2021_2_OR_NEWER
        PlayerSettings.SetApplicationIdentifier(
            NamedBuildTarget.Android,
            value);
#else
        PlayerSettings.SetApplicationIdentifier(
            BuildTargetGroup.Android,
            value);
#endif
    }

    private static string GetAndroidApplicationIdentifier()
    {
#if UNITY_2021_2_OR_NEWER
        return PlayerSettings.GetApplicationIdentifier(
            NamedBuildTarget.Android);
#else
        return PlayerSettings.GetApplicationIdentifier(
            BuildTargetGroup.Android);
#endif
    }

    private static void SetAndroidScriptingDefineSymbols(string value)
    {
#if UNITY_2021_2_OR_NEWER
        PlayerSettings.SetScriptingDefineSymbols(
            NamedBuildTarget.Android,
            value);
#else
        PlayerSettings.SetScriptingDefineSymbolsForGroup(
            BuildTargetGroup.Android,
            value);
#endif
    }

    private static string GetAndroidScriptingDefineSymbols()
    {
#if UNITY_2021_2_OR_NEWER
        return PlayerSettings.GetScriptingDefineSymbols(
            NamedBuildTarget.Android);
#else
        return PlayerSettings.GetScriptingDefineSymbolsForGroup(
            BuildTargetGroup.Android);
#endif
    }

    private static void SetIosApplicationIdentifier(string value)
    {
#if UNITY_2021_2_OR_NEWER
        PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.iOS, value);
#else
        PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.iOS, value);
#endif
    }

    private static void SetIosScriptingDefineSymbols(string value)
    {
#if UNITY_2021_2_OR_NEWER
        PlayerSettings.SetScriptingDefineSymbols(NamedBuildTarget.iOS, value);
#else
        PlayerSettings.SetScriptingDefineSymbolsForGroup(BuildTargetGroup.iOS, value);
#endif
    }

    private static void SetWindowsScriptingDefineSymbols(string value)
    {
#if UNITY_2021_2_OR_NEWER
        PlayerSettings.SetScriptingDefineSymbols(NamedBuildTarget.Standalone, value);
#else
        PlayerSettings.SetScriptingDefineSymbolsForGroup(BuildTargetGroup.Standalone, value);
#endif
    }

    private static string GetWindowsScriptingDefineSymbols()
    {
#if UNITY_2021_2_OR_NEWER
        return PlayerSettings.GetScriptingDefineSymbols(NamedBuildTarget.Standalone);
#else
        return PlayerSettings.GetScriptingDefineSymbolsForGroup(BuildTargetGroup.Standalone);
#endif
    }

    private static void Log(string message) { Debug.Log($"{LogPrefix} {message}"); }

    private static void Warning(string message)
    {
        Debug.LogWarning($"{LogPrefix} {message}");
    }

    private static void Error(string message) { Debug.LogError($"{LogPrefix} {message}"); }

    [Serializable]
    private sealed class BuildMetadata
    {
        public int      schemaVersion;
        public string   result;
        public string   errorMessage;
        public string   productName;
        public string   bundleIdentifier;
        public string   versionName;
        public int      androidVersionCode;
        public string   unityVersion;
        public string   scriptingBackend;
        public string   managedStrippingLevel;
        public string   orientation;
        public string   targetArchitectures;
        public string[] scriptingDefineSymbols;
        public string   buildConfiguration;
        public bool     buildAppBundle;
        public string   outputFileName;
        public long     outputSizeBytes;
        public double   buildDurationSeconds;
        public int      warningCount;
        public int      errorCount;
        public string   mappingFileName;
        public long     mappingSizeBytes;
        public string   generatedAtUtc;
    }

    private sealed class BuildConfiguration
    {
        public string OutputPath            { get; set; }
        public string ConfigurationName     { get; set; }
        public bool   DevelopmentBuild      { get; set; }
        public bool   UnityDevelopmentBuild { get; set; }
        public bool   ScriptDebugging       { get; set; }
        public bool   BuildAppBundle        { get; set; }

        public string TargetArchitectures   { get; set; }
        public string Il2CppCodeGeneration  { get; set; }
        public string ManagedStrippingLevel { get; set; }
        public bool?  StripEngineCode       { get; set; }
        public bool?  MinifyRelease         { get; set; }

        public string ProductName            { get; set; }
        public string BundleIdentifier       { get; set; }
        public string ScriptingDefineSymbols { get; set; }

        public string AppVersion         { get; set; }
        public int    AndroidVersionCode { get; set; }

        public string KeystorePath     { get; set; }
        public string KeystorePassword { get; set; }
        public string KeyAliasName     { get; set; }
        public string KeyAliasPassword { get; set; }
    }
    private sealed class IosBuildConfiguration
    {
        public string OutputPath { get; set; }
        public bool UnityDevelopmentBuild { get; set; }
        public bool ScriptDebugging { get; set; }
        public string ProductName { get; set; }
        public string BundleIdentifier { get; set; }
        public string ScriptingDefineSymbols { get; set; }
        public string AppVersion { get; set; }
        public string BuildNumber { get; set; }
        public string Il2CppCodeGeneration { get; set; }
        public string ManagedStrippingLevel { get; set; }
        public bool? StripEngineCode { get; set; }
    }

    private sealed class WindowsBuildConfiguration
    {
        public string OutputPath { get; set; }
        public bool UnityDevelopmentBuild { get; set; }
        public bool ScriptDebugging { get; set; }
        public string ProductName { get; set; }
        public string ScriptingDefineSymbols { get; set; }
        public string AppVersion { get; set; }
        public string Il2CppCodeGeneration { get; set; }
        public string ManagedStrippingLevel { get; set; }
        public bool? StripEngineCode { get; set; }
    }

    [Serializable]
    private sealed class WindowsBuildMetadata
    {
        public int schemaVersion;
        public string result;
        public string errorMessage;
        public string productName;
        public string versionName;
        public string unityVersion;
        public string scriptingBackend;
        public string managedStrippingLevel;
        public string[] scriptingDefineSymbols;
        public string outputFileName;
        public long outputSizeBytes;
        public double buildDurationSeconds;
        public int warningCount;
        public int errorCount;
        public string generatedAtUtc;
    }
}
}

#endif
