cask "dialex" do
  version "1.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"

  url "https://github.com/Kolta-Labs/DialexAI/releases/download/v#{version}/DialexAI-#{version}-macOS-arm64.dmg"
  name "Dialex AI"
  desc "Sovereign Multi-AI Deliberation & Synthetic Advisory Platform"
  homepage "https://github.com/Kolta-Labs/DialexAI"

  depends_on formula: "kolta-labs/dialex/dialex"

  app "DialexAI.app", target: "Dialex.app"

  zap trash: [
    "~/.dialex",
    "~/Library/Application Support/Dialex",
    "~/Library/Preferences/com.koltalabs.dialex.plist",
    "~/Library/Saved Application State/com.koltalabs.dialex.savedState",
  ]
end
