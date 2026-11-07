#!/usr/bin/env bash

set -euo pipefail

usage() {
    cat <<'EOF'
Usage: generate-app-icon.sh --output DIR [--url URL | --path PATH] [--favicon-from URL]

Reads or downloads an icon and generates Android launcher resources. If no icon
source is provided, --favicon-from tries /favicon.ico and falls back to a built-in icon.
EOF
}

icon_url=""
icon_path=""
favicon_from=""
output_dir=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --url)
            icon_url=${2-}
            shift 2
            ;;
        --path)
            icon_path=${2-}
            shift 2
            ;;
        --favicon-from)
            favicon_from=${2-}
            shift 2
            ;;
        --output)
            output_dir=${2-}
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [ -z "$output_dir" ]; then
    echo "--output is required" >&2
    exit 2
fi

if [ -n "$icon_url" ] && [ -n "$icon_path" ]; then
    echo "--url and --path cannot be used together" >&2
    exit 2
fi

# The script replaces this directory atomically, so restrict it to its dedicated
# generated-resource path.
case "$output_dir" in
    */generated/appIcon/res) ;;
    *)
        echo "Refusing to replace unexpected output directory: $output_dir" >&2
        exit 2
        ;;
esac

temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT
downloaded_icon="$temp_dir/source-icon"
staged_res="$temp_dir/res"
mkdir -p "$staged_res"

download_icon() {
    local url=$1
    if ! curl \
        --proto '=http,https' \
        --fail \
        --location \
        --silent \
        --show-error \
        --retry 2 \
        --connect-timeout 15 \
        --max-time 60 \
        --user-agent 'webview-apk-template icon generator' \
        --output "$downloaded_icon" \
        "$url"; then
        return 1
    fi

    local size
    size=$(wc -c < "$downloaded_icon")
    if [ "$size" -eq 0 ] || [ "$size" -gt 20971520 ]; then
        echo "Downloaded icon must be between 1 byte and 20 MiB (received $size bytes)" >&2
        return 1
    fi
}

using_custom_icon=false
using_explicit_icon=false
icon_source_label=""
if [ -n "$icon_url" ]; then
    case "$icon_url" in
        http://*|https://*) ;;
        *)
            echo "Icon URL must use http:// or https://: $icon_url" >&2
            exit 1
            ;;
    esac
    echo "Downloading app icon: $icon_url"
    download_icon "$icon_url"
    using_custom_icon=true
    using_explicit_icon=true
    icon_source_label=$icon_url
elif [ -n "$icon_path" ]; then
    if [ ! -f "$icon_path" ]; then
        echo "App icon file does not exist or is not a regular file: $icon_path" >&2
        exit 1
    fi
    icon_size=$(wc -c < "$icon_path")
    if [ "$icon_size" -eq 0 ] || [ "$icon_size" -gt 20971520 ]; then
        echo "App icon file must be between 1 byte and 20 MiB (received $icon_size bytes)" >&2
        exit 1
    fi
    echo "Reading app icon: $icon_path"
    cp "$icon_path" "$downloaded_icon"
    using_custom_icon=true
    using_explicit_icon=true
    icon_source_label=$icon_path
elif [ -n "$favicon_from" ]; then
    if [[ "$favicon_from" =~ ^(https?://[^/]+) ]]; then
        favicon_url="${BASH_REMATCH[1]}/favicon.ico"
        echo "No icon URL supplied; trying $favicon_url"
        if download_icon "$favicon_url"; then
            using_custom_icon=true
        else
            echo "Warning: favicon.ico was unavailable; using the built-in icon" >&2
        fi
    else
        echo "Warning: cannot derive favicon.ico from URL; using the built-in icon" >&2
    fi
fi

write_adaptive_icon_xml() {
    local path=$1
    mkdir -p "$(dirname "$path")"
    cat > "$path" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
EOF
}

using_android_vector=false
if [ "$using_custom_icon" = true ]; then
    if command -v xmllint >/dev/null 2>&1; then
        xml_root=$(xmllint --xpath 'local-name(/*)' "$downloaded_icon" 2>/dev/null || true)
        if [ "$xml_root" = "vector" ]; then
            android_namespace=$(xmllint \
                --xpath 'string(/*/namespace::*[name()="android"])' \
                "$downloaded_icon" 2>/dev/null || true)
            if [ "$android_namespace" != "http://schemas.android.com/apk/res/android" ]; then
                echo "Android Vector Drawable is missing the standard android namespace" >&2
                exit 1
            fi
            using_android_vector=true
        fi
    elif [ "$(file --brief --mime-type "$downloaded_icon")" = "application/xml" ] ||
        [ "$(file --brief --mime-type "$downloaded_icon")" = "text/xml" ]; then
        echo "libxml2-utils is required for Android Vector Drawable XML (install 'xmllint')" >&2
        exit 1
    fi
fi

if [ "$using_custom_icon" = true ] && [ "$using_android_vector" = false ]; then
    if command -v magick >/dev/null 2>&1; then
        imagemagick=(magick)
    elif command -v convert >/dev/null 2>&1 && command -v identify >/dev/null 2>&1; then
        imagemagick=(convert)
    else
        echo "ImageMagick is required for this icon format (install 'imagemagick')" >&2
        exit 1
    fi

    identify_command=("${imagemagick[@]}")
    if [ "${imagemagick[0]}" = "magick" ]; then
        identify_command+=(identify)
    else
        identify_command=(identify)
    fi

    input_source=$downloaded_icon
    # ImageMagick does not sniff ICO data when the downloaded temporary file has
    # no extension, so select the decoder from the ICO header when necessary.
    if [ "$(od -An -tx1 -N4 "$downloaded_icon" | tr -d ' \n')" = "00000100" ]; then
        input_source="ico:$downloaded_icon"
    fi

    if ! frame_info=$("${identify_command[@]}" -ping -format '%s %w %h\n' "$input_source" 2>/dev/null); then
        if [ "$using_explicit_icon" = true ]; then
            echo "The icon is not an image supported by ImageMagick: $icon_source_label" >&2
            exit 1
        fi
        echo "Warning: favicon.ico is not a supported image; using the built-in icon" >&2
        using_custom_icon=false
    fi
fi

if [ "$using_android_vector" = true ]; then
    mkdir -p "$staged_res/mipmap-anydpi"
    cp "$downloaded_icon" "$staged_res/mipmap-anydpi/ic_launcher.xml"
    cp "$downloaded_icon" "$staged_res/mipmap-anydpi/ic_launcher_round.xml"
    echo "Using Android Vector Drawable directly as the launcher icon"
elif [ "$using_custom_icon" = true ]; then
    best_frame=0
    best_area=0
    best_width=0
    best_height=0
    while read -r frame width height; do
        if [[ "$frame" =~ ^[0-9]+$ && "$width" =~ ^[0-9]+$ && "$height" =~ ^[0-9]+$ ]]; then
            area=$((width * height))
            if [ "$area" -gt "$best_area" ]; then
                best_frame=$frame
                best_area=$area
                best_width=$width
                best_height=$height
            fi
        fi
    done <<< "$frame_info"

    if [ "$best_area" -eq 0 ]; then
        echo "Could not determine the downloaded icon dimensions" >&2
        exit 1
    fi

    source_frame="${input_source}[${best_frame}]"
    densities=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
    legacy_sizes=(48 72 96 144 192)
    foreground_sizes=(108 162 216 324 432)
    foreground_art_sizes=(72 108 144 216 288)

    for index in "${!densities[@]}"; do
        density=${densities[$index]}
        legacy_size=${legacy_sizes[$index]}
        foreground_size=${foreground_sizes[$index]}
        foreground_art_size=${foreground_art_sizes[$index]}

        mkdir -p "$staged_res/mipmap-$density" "$staged_res/drawable-$density"
        "${imagemagick[@]}" \
            -limit memory 256MiB -limit map 512MiB -limit disk 1GiB \
            "$source_frame" -auto-orient -background none -alpha on -trim +repage \
            -resize "${legacy_size}x${legacy_size}" \
            -gravity center -extent "${legacy_size}x${legacy_size}" \
            "PNG32:$staged_res/mipmap-$density/ic_launcher.png"
        cp "$staged_res/mipmap-$density/ic_launcher.png" \
            "$staged_res/mipmap-$density/ic_launcher_round.png"

        "${imagemagick[@]}" \
            -limit memory 256MiB -limit map 512MiB -limit disk 1GiB \
            "$source_frame" -auto-orient -background none -alpha on -trim +repage \
            -resize "${foreground_art_size}x${foreground_art_size}" \
            -gravity center -extent "${foreground_size}x${foreground_size}" \
            "PNG32:$staged_res/drawable-$density/ic_launcher_foreground.png"
    done

    mkdir -p "$staged_res/values"
    cat > "$staged_res/values/ic_launcher_colors.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FFFFFF</color>
</resources>
EOF
    write_adaptive_icon_xml "$staged_res/mipmap-anydpi-v26/ic_launcher.xml"
    write_adaptive_icon_xml "$staged_res/mipmap-anydpi-v26/ic_launcher_round.xml"
    echo "Generated launcher icons from a ${best_width}x${best_height} source frame"
else
    mkdir -p "$staged_res/values"
    cat > "$staged_res/values/ic_launcher.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <item name="ic_launcher" type="mipmap">@android:mipmap/sym_def_app_icon</item>
    <item name="ic_launcher_round" type="mipmap">@android:mipmap/sym_def_app_icon</item>
</resources>
EOF
    echo "Using the Android system default launcher icon"
fi

mkdir -p "$(dirname "$output_dir")"
rm -rf "$output_dir"
mv "$staged_res" "$output_dir"
