#!/usr/bin/env bash
# Build Xcode project Unity đã export và cài lên iPhone đang cắm.
# Env: IOS_PROJECT_PATH, IOS_DEVICE_UDID, IOS_PROFILE_SPECIFIER (tuỳ chọn),
# XCODE_CONFIGURATION, IOS_DESTINATION_TIMEOUT, DERIVED_DATA_PATH, ...
set -eu
[ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" ] || {
    echo "ERROR: Unity-iPhone.xcodeproj was not exported."
    exit 1
}

# IOS_DEVICE_UDID trống: tự dò iPhone đã kết nối.
# Ưu tiên máy cắm dây, đã pair. Một số phiên bản
# CoreDevice mới đưa các trường này vào properties
# (thay cho các trường *Properties cũ). Hỗ trợ cả
# hai schema, và fallback State=connected khi các
# chi tiết pair/transport không có. Đúng 1 máy thì
# dùng luôn; 0 hoặc nhiều máy thì báo lỗi kèm danh
# sách để chỉ định UDID.
# tunnelState không dùng để lọc vì máy cắm dây vẫn
# hiện "disconnected" khi không debug. Lấy
# hardwareProperties.udid (đúng định dạng
# xcodebuild -destination id= và devicectl).
if [ -z "${IOS_DEVICE_UDID:-}" ]; then
    echo 'IOS_DEVICE_UDID trống — tự dò thiết bị đang cắm...'
    dev_json="$(mktemp -t devicectl-devices)"
    if ! xcrun devicectl list devices --json-output "$dev_json" >/dev/null 2>&1; then
        rm -f "$dev_json"
        echo 'ERROR: không chạy được "xcrun devicectl list devices".'
        exit 1
    fi
    candidates="$(ruby -rjson -e '
        data = (JSON.parse(File.read(ARGV[0])) rescue nil)
        exit(0) unless data
        devices = data.dig("result", "devices") || []
        strict = devices.select do |dev|
          properties = dev["properties"] || {}
          hw = properties["hardware"] || dev["hardwareProperties"] || {}
          cp = properties["connection"] || dev["connectionProperties"] || {}
          hw["reality"] == "physical" &&
            cp["pairingState"] == "paired" &&
            cp["transportType"] == "wired"
        end
        # Xcode/CoreDevice can omit the two connection
        # fields after a newly trusted device is attached.
        # A physical device reported as connected is still a
        # valid devicectl/xcodebuild target.
        connected = devices.select do |dev|
          properties = dev["properties"] || {}
          hw = properties["hardware"] || dev["hardwareProperties"] || {}
          cp = properties["connection"] || dev["connectionProperties"] || {}
          state = cp["state"] || dev["state"] || dev.dig("deviceProperties", "state")
          hw["reality"] == "physical" && state == "connected"
        end
        # Máy đã pair nhưng tunnel CoreDevice chưa mở hiện
        # "available (paired)" (hay gặp khi màn hình khóa
        # hoặc kết nối qua mạng). devicectl tự mở kết nối khi
        # install, nên vẫn nhận làm target.
        paired = devices.select do |dev|
          properties = dev["properties"] || {}
          hw = properties["hardware"] || dev["hardwareProperties"] || {}
          cp = properties["connection"] || dev["connectionProperties"] || {}
          hw["reality"] == "physical" && cp["pairingState"] == "paired"
        end
        picked = [strict, connected, paired].find { |list| !list.empty? } || []
        picked.each do |dev|
          properties = dev["properties"] || {}
          hw = properties["hardware"] || dev["hardwareProperties"] || {}
          state = properties["state"] || dev["deviceProperties"] || {}
          name = state["name"]
          puts "#{hw["udid"]}\t#{name} (#{hw["marketingName"]})"
        end
    ' "$dev_json")"
    rm -f "$dev_json"
    count="$(echo "$candidates" | awk 'NF' | wc -l | tr -d ' ')"
    if [ "$count" -eq 0 ]; then
        echo 'ERROR: không thấy iPhone nào đã pair với máy Mac này.'
        echo 'Cắm máy + Trust trên máy, hoặc điền IOS_DEVICE_UDID. Danh sách hiện có:'
        xcrun devicectl list devices || true
        exit 1
    fi
    if [ "$count" -gt 1 ]; then
        echo 'ERROR: có nhiều máy cắm cùng lúc — điền IOS_DEVICE_UDID một trong các máy sau:'
        echo "$candidates"
        exit 1
    fi
    IOS_DEVICE_UDID="$(echo "$candidates" | awk 'NF' | head -n1 | cut -f1)"
    echo "Đã tự dò UDID: $IOS_DEVICE_UDID"
fi

rm -rf "$DERIVED_DATA_PATH"
echo 'iOS device bundle identifier is sourced from Unity Project Settings.'

# Personal Teams cannot provision the In-App Purchase capability.
# This is a development-only device build, so remove it from the
# generated Xcode project without changing the Unity source project
# or the normal IPA-export pipeline.
pbxproj_path="$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj/project.pbxproj"
if grep -Fq 'com.apple.InAppPurchase' "$pbxproj_path"; then
    perl -0pi -e 's/\s*com\.apple\.InAppPurchase\s*=\s*\{\s*enabled\s*=\s*1;\s*\};\s*//g' "$pbxproj_path"
    echo 'Removed In-App Purchase capability for Personal Team device signing.'
fi
find "$IOS_PROJECT_PATH" -name '*.entitlements' -type f -print |
    while IFS= read -r entitlements_path; do
        /usr/libexec/PlistBuddy \
            -c 'Delete :com.apple.developer.in-app-payments' \
            "$entitlements_path" >/dev/null 2>&1 || true
    done

# Mặc định của Xcode chỉ chờ destination 30 giây. Máy đang
# ở giữa bước "Preparing device for development" - hay gặp
# sau khi cập nhật iOS - thì quá ngắn, build hỏng dù một
# phút sau máy đã sẵn sàng. Chờ lâu hơn không cứu được
# ghép đôi hỏng thật, chỉ bỏ qua lúc máy chậm sẵn sàng.
# Build unsigned cho generic iOS thay vì id của máy: Xcode
# chỉ liệt kê iPhone làm destination khi tunnel CoreDevice
# đang mở, còn máy "available (paired)" (màn khóa, qua
# mạng) bị báo "Unable to find a destination". Bản build
# không ký nên không cần máy thật; devicectl cài sau.
run_xcodebuild_unsigned() {
    xcodebuild_args="-scheme Unity-iPhone -configuration $XCODE_CONFIGURATION -destination generic/platform=iOS -destination-timeout $IOS_DESTINATION_TIMEOUT -derivedDataPath $DERIVED_DATA_PATH CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build"
    if [ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcworkspace" ]; then
        xcodebuild -workspace "$IOS_PROJECT_PATH/Unity-iPhone.xcworkspace" $xcodebuild_args > "$XCODEBUILD_LOG_PATH" 2>&1
    else
        xcodebuild -project "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" $xcodebuild_args > "$XCODEBUILD_LOG_PATH" 2>&1
    fi
}

set +e
# Để trống cũng được: sau khi build xong sẽ tự suy
# tên profile development từ bundle id của app.
profile_specifier="${IOS_PROFILE_SPECIFIER:-}"

# Build unsigned first. Xcode 26 performs provisioning validation
# before it builds and rejects Personal Team profiles for Unity IAP,
# even after the capability is removed. We then sign the built app
# directly with the installed development profile.
echo 'Building unsigned iOS app for direct development signing.'
run_xcodebuild_unsigned
result=$?
cat "$XCODEBUILD_LOG_PATH"
[ "$result" -eq 0 ] || exit "$result"

app_path="$(find "$DERIVED_DATA_PATH/Build/Products"                                     -type d -name '*.app' -print -quit)"
[ -d "$app_path" ] || {
    echo 'ERROR: Xcode did not produce an iOS .app bundle.'
    exit 1
}
echo "Built iOS app: $app_path"

# Chưa chỉ định profile thì tự suy tên profile
# development Xcode-managed từ bundle id của app —
# luôn có dạng "iOS Team Provisioning Profile:
# <bundle id>". Bundle id lấy từ Info.plist của .app
# vừa build (đã resolve, đúng cho từng game), nên mỗi
# job khỏi khai báo profile trong pipeline script.
if [ -z "$profile_specifier" ]; then
    bundle_id="$(/usr/libexec/PlistBuddy \
        -c 'Print :CFBundleIdentifier' \
        "$app_path/Info.plist" 2>/dev/null || true)"
    [ -n "$bundle_id" ] || {
        echo 'ERROR: không đọc được CFBundleIdentifier để tự suy provisioning profile.'
        echo 'Khắc phục: điền iosDeviceProvisioningProfileSpecifier trong pipeline script hoặc param IOS_PROVISIONING_PROFILE_SPECIFIER.'
        exit 2
    }
    profile_specifier="iOS Team Provisioning Profile: $bundle_id"
    echo "Tự suy provisioning profile theo bundle id: $profile_specifier"
fi

# Có thể có nhiều profile cùng tên (bản cũ thiếu máy, bản Xcode
# vừa tạo lại): ưu tiên profile chứa UDID máy đang cắm, không có
# thì lấy profile khớp tên đầu tiên.
find_installed_profile() {
    profile_path=''
    for profiles_dir in \
        "$HOME/Library/MobileDevice/Provisioning Profiles" \
        "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
        [ -d "$profiles_dir" ] || continue
        for candidate_profile in "$profiles_dir"/*.mobileprovision; do
            [ -f "$candidate_profile" ] || continue
            candidate_plist="$(security cms -D -i "$candidate_profile" 2>/dev/null || true)"
            candidate_name="$(printf '%s' "$candidate_plist" | \
                plutil -extract Name raw - 2>/dev/null || true)"
            [ "$candidate_name" = "$profile_specifier" ] || continue
            [ -n "$profile_path" ] || profile_path="$candidate_profile"
            if printf '%s' "$candidate_plist" | grep -qi "<string>$IOS_DEVICE_UDID</string>"; then
                profile_path="$candidate_profile"
                return 0
            fi
        done
    done
    [ -n "$profile_path" ]
}

# Chưa có profile (game mới / đổi bundle id / Personal Team
# hết hạn 7 ngày): cho Xcode tự tạo như bấm Run trong Xcode.
# Cần Apple ID đã đăng nhập trong Xcode > Settings > Accounts
# dưới user chạy Jenkins. Team lấy từ IOS_DEVELOPMENT_TEAM
# hoặc OU của chứng chỉ "Apple Development" trong keychain.
# Bản build này chỉ để Xcode sinh profile; kết quả build
# (kể cả lỗi validate IAP của Personal Team) bỏ qua.
auto_provision_profile() {
    team="${IOS_DEVELOPMENT_TEAM:-}"
    if [ -z "$team" ]; then
        team="$(security find-certificate -c 'Apple Development' -p 2>/dev/null | \
            openssl x509 -noout -subject -nameopt multiline 2>/dev/null | \
            awk -F' = ' '/organizationalUnitName/ {print $2; exit}')"
    fi
    [ -n "$team" ] || {
        echo 'WARN: không xác định được Team ID để tự tạo profile (điền IOS_DEVELOPMENT_TEAM).'
        return 1
    }
    echo "Không thấy profile — nhờ Xcode tự tạo cho team $team (-allowProvisioningUpdates)..."
    provision_log="$DERIVED_DATA_PATH/provisioning.log"
    if [ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcworkspace" ]; then
        container_args="-workspace $IOS_PROJECT_PATH/Unity-iPhone.xcworkspace"
    else
        container_args="-project $IOS_PROJECT_PATH/Unity-iPhone.xcodeproj"
    fi
    # Đích phải là đúng máy (không phải generic/platform=iOS):
    # team chưa có thiết bị nào thì Xcode chỉ đăng ký được máy
    # nằm trong -destination, generic thì báo "no devices".
    # shellcheck disable=SC2086
    xcodebuild $container_args -scheme Unity-iPhone \
        -configuration "$XCODE_CONFIGURATION" \
        -destination "id=$IOS_DEVICE_UDID" -destination-timeout 120 \
        -derivedDataPath "$DERIVED_DATA_PATH/Provisioning" \
        -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
        CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM="$team" \
        CODE_SIGN_IDENTITY='Apple Development' PROVISIONING_PROFILE_SPECIFIER= \
        build > "$provision_log" 2>&1
    grep -E 'error:|Provisioning|profile' "$provision_log" | tail -n 20 || true
}

profile_plist="$DERIVED_DATA_PATH/provisioning-profile.plist"

# Profile development chỉ cài được lên máy có UDID trong
# ProvisionedDevices. Profile do archive IPA sinh ra (không có
# đích là máy) có thể thiếu máy đang cắm.
profile_has_device() {
    security cms -D -i "$profile_path" > "$profile_plist" 2>/dev/null || return 1
    /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "$profile_plist" 2>/dev/null | \
        grep -qi "$IOS_DEVICE_UDID"
}

if ! find_installed_profile || ! profile_has_device; then
    [ -z "$profile_path" ] || echo "Profile hiện có không chứa máy $IOS_DEVICE_UDID — nhờ Xcode đăng ký máy và tạo lại profile."
    auto_provision_profile || true
    find_installed_profile || {
        echo "ERROR: Installed provisioning profile was not found: $profile_specifier"
        echo 'Xcode không tự tạo được profile. Kiểm tra: Apple ID đã đăng nhập trong Xcode > Settings > Accounts (đúng user chạy Jenkins), iPhone đang mở khoá + kết nối, bundle id chưa bị Apple ID khác dùng.'
        echo "Log chi tiết: $DERIVED_DATA_PATH/provisioning.log"
        exit 3
    }
    profile_has_device || {
        echo "ERROR: Profile $profile_specifier không chứa máy $IOS_DEVICE_UDID."
        echo 'Máy chưa được đăng ký trong team (vai trò Developer có thể không được đăng ký thiết bị). Nhờ Admin thêm UDID ở developer.apple.com > Devices rồi build lại.'
        echo "Log chi tiết: $DERIVED_DATA_PATH/provisioning.log"
        exit 3
    }
fi
echo "Dùng provisioning profile: $profile_path"

signing_entitlements="$DERIVED_DATA_PATH/signing-entitlements.plist"
plutil -extract Entitlements xml1 -o "$signing_entitlements" "$profile_plist"
cp "$profile_path" "$app_path/embedded.mobileprovision"

# Ký bằng đúng chứng chỉ nằm trong profile (theo SHA-1): keychain có
# nhiều "Apple Development" của các team khác nhau thì
# --sign 'Apple Development' báo ambiguous và để app không được ký.
signing_identity=''
installed_identities="$(security find-identity -v -p codesigning 2>/dev/null)"
cert_index=0
while cert_xml="$(plutil -extract "DeveloperCertificates.$cert_index" xml1 -o - "$profile_plist" 2>/dev/null)"; do
    cert_sha1="$(printf '%s\n' "$cert_xml" | \
        sed -n '/<data>/,/<\/data>/p' | sed -e 's/<[^>]*>//g' | tr -d ' \t\n' | \
        base64 -D 2>/dev/null | shasum -a 1 | awk '{print toupper($1)}')"
    if [ -n "$cert_sha1" ] && printf '%s\n' "$installed_identities" | grep -q "$cert_sha1"; then
        signing_identity="$cert_sha1"
        break
    fi
    cert_index=$((cert_index + 1))
done
[ -n "$signing_identity" ] || {
    echo 'ERROR: keychain không có chứng chỉ Apple Development nào nằm trong profile này.'
    exit 4
}

echo "Signing iOS device app with installed profile: $profile_specifier (identity $signing_identity)"
sign_failed=0
# -exec ... + trả mã lỗi khác 0 nếu có lần codesign hỏng.
find "$app_path" -depth -type d \
    \( -name '*.framework' -o -name '*.appex' \) \
    -exec codesign --force --sign "$signing_identity" --timestamp=none {} + || sign_failed=1
find "$app_path" -type f -name '*.dylib' \
    -exec codesign --force --sign "$signing_identity" --timestamp=none {} + || sign_failed=1
codesign --force --sign "$signing_identity" \
    --entitlements "$signing_entitlements" \
    --timestamp=none "$app_path" || sign_failed=1
codesign --verify --deep --strict "$app_path" || sign_failed=1
[ "$sign_failed" -eq 0 ] || {
    echo 'ERROR: ký app thất bại; không cài lên máy.'
    exit 4
}

xcrun devicectl device install app \
    --device "$IOS_DEVICE_UDID" "$app_path" \
    >> "$XCODEBUILD_LOG_PATH" 2>&1
result=$?
cat "$XCODEBUILD_LOG_PATH"
[ "$result" -eq 0 ] && \
    echo 'iOS app installed successfully on the connected device.'
exit "$result"
