#include "policy.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

#include <algorithm>
#include <cctype>
#include <cstring>

namespace otetv {
namespace {

// Frames the official player legitimately embeds: the player itself, consent, sign-in checks and the provider's
// advertising. Filtering stays a destination policy, never an ad blocker (brief section 16.2).
const char* const kFrameSuffixes[] = {
    "youtube.com", "youtube-nocookie.com", "google.com", "googleapis.com", "gstatic.com",
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
};

std::string Lower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return s;
}

bool HttpsDnsHost(const UrlParts& u) {
  return u.scheme == "https" && IsDnsName(u.host);
}

}  // namespace

bool IsPublicV4(const uint8_t a[4]) {
  int b0 = a[0], b1 = a[1], b2 = a[2], b3 = a[3];
  if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) return false;  // broadcast
  if (b0 == 0) return false;                                            // unspecified / this network
  if (b0 == 10) return false;                                           // private
  if (b0 == 100 && (b1 & 0xC0) == 64) return false;                     // carrier-grade NAT
  if (b0 == 127) return false;                                          // loopback
  if (b0 == 169 && b1 == 254) return false;                             // link-local, cloud metadata
  if (b0 == 172 && (b1 & 0xF0) == 16) return false;                     // private
  if (b0 == 192 && b1 == 0 && b2 == 0) return false;                    // IETF protocol assignments
  if (b0 == 192 && b1 == 0 && b2 == 2) return false;                    // documentation
  if (b0 == 192 && b1 == 88 && b2 == 99) return false;                  // 6to4 relay anycast
  if (b0 == 192 && b1 == 168) return false;                             // private
  if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;                // benchmarking
  if (b0 == 198 && b1 == 51 && b2 == 100) return false;                 // documentation
  if (b0 == 203 && b1 == 0 && b2 == 113) return false;                  // documentation
  if (b0 >= 224) return false;                                          // multicast and reserved
  return true;
}

bool IsPublicV6(const uint8_t a[16]) {
  bool zero10 = std::all_of(a, a + 10, [](uint8_t b) { return b == 0; });
  if (zero10) {
    if (a[10] == 0 && a[11] == 0) {
      return false;  // unspecified, loopback, or deprecated IPv4-compatible: never better than reserved
    }
    if (a[10] == 0xFF && a[11] == 0xFF) {
      return IsPublicV4(a + 12);  // IPv4-mapped
    }
  }
  int w0 = (a[0] << 8) | a[1];
  int w1 = (a[2] << 8) | a[3];
  if (w0 == 0x0064 && w1 == 0xff9b) {
    bool well_known = std::all_of(a + 4, a + 12, [](uint8_t b) { return b == 0; });
    return well_known && IsPublicV4(a + 12);  // NAT64; 64:ff9b:1::/48 is local-use
  }
  if (w0 == 0x0100 && w1 == 0 && a[4] == 0 && a[5] == 0 && a[6] == 0 && a[7] == 0) return false;  // discard-only
  if (w0 == 0x2001) {
    if (w1 == 0x0db8) return false;                                           // documentation
    if (w1 == 0x0000) return false;                                           // Teredo
    if ((w1 & 0xFFF0) == 0x0010 || (w1 & 0xFFF0) == 0x0020) return false;     // ORCHID
    if (w1 < 0x0200) return false;                                            // IETF protocol assignments
  }
  if (w0 == 0x2002) {
    uint8_t v4[4] = {a[2], a[3], a[4], a[5]};
    return IsPublicV4(v4);  // 6to4 embeds the relay address
  }
  if ((w0 & 0xFE00) == 0xFC00) return false;  // unique local
  if ((w0 & 0xFFC0) == 0xFE80) return false;  // link-local
  if ((w0 & 0xFFC0) == 0xFEC0) return false;  // deprecated site-local
  if ((w0 & 0xFF00) == 0xFF00) return false;  // multicast
  return (w0 & 0xE000) == 0x2000;             // global unicast only
}

bool IsPublicAddress(const sockaddr* address) {
  if (address->sa_family == AF_INET) {
    const auto* in = reinterpret_cast<const sockaddr_in*>(address);
    uint8_t a[4];
    std::memcpy(a, &in->sin_addr, 4);
    return IsPublicV4(a);
  }
  if (address->sa_family == AF_INET6) {
    const auto* in6 = reinterpret_cast<const sockaddr_in6*>(address);
    uint8_t a[16];
    std::memcpy(a, &in6->sin6_addr, 16);
    return IsPublicV6(a);
  }
  return false;
}

bool ClassifyLiteral(const std::string& host, bool* is_public) {
  std::string h = host;
  if (h.size() >= 2 && h.front() == '[' && h.back() == ']') {
    h = h.substr(1, h.size() - 2);
  }
  uint8_t v4[4];
  uint8_t v6[16];
  if (inet_pton(AF_INET, h.c_str(), v4) == 1) {
    *is_public = IsPublicV4(v4);
    return true;
  }
  if (inet_pton(AF_INET6, h.c_str(), v6) == 1) {
    *is_public = IsPublicV6(v6);
    return true;
  }
  return false;
}

bool IsDnsName(const std::string& host) {
  if (host.empty() || host.size() > 253 || host.front() == '.' || host.front() == '-') {
    return false;
  }
  bool is_public = false;
  if (ClassifyLiteral(host, &is_public)) {
    return false;
  }
  bool any_letter = false;
  for (char c : host) {
    if (std::isalpha(static_cast<unsigned char>(c))) {
      any_letter = true;
    } else if (!std::isdigit(static_cast<unsigned char>(c)) && c != '-' && c != '.') {
      return false;
    }
  }
  // An all-numeric name would be an alternate IPv4 spelling ("2130706433", "127.1"); refuse it outright.
  return any_letter;
}

bool HostMatches(const std::string& host, const std::string& suffix) {
  if (host == suffix) {
    return true;
  }
  return host.size() > suffix.size() + 1 && host.compare(host.size() - suffix.size(), suffix.size(), suffix) == 0 &&
         host[host.size() - suffix.size() - 1] == '.';
}

UrlParts ParseUrl(const std::string& url) {
  UrlParts parts;
  size_t colon = url.find(':');
  if (colon == std::string::npos) {
    return parts;
  }
  parts.scheme = Lower(url.substr(0, colon));
  if (url.compare(colon, 3, "://") != 0) {
    return parts;  // opaque URL such as data: or about:blank
  }
  size_t start = colon + 3;
  size_t end = url.find_first_of("/?#", start);
  std::string authority = url.substr(start, end == std::string::npos ? std::string::npos : end - start);
  size_t at = authority.rfind('@');
  if (at != std::string::npos) {
    authority = authority.substr(at + 1);
  }
  if (!authority.empty() && authority.front() == '[') {
    size_t close = authority.find(']');
    parts.host = close == std::string::npos ? authority : authority.substr(0, close + 1);
  } else {
    size_t port = authority.rfind(':');
    parts.host = port == std::string::npos ? authority : authority.substr(0, port);
  }
  parts.host = Lower(parts.host);
  return parts;
}

bool AllowMainFrameNavigation(const std::string& url) {
  if (url == "about:blank") {
    return true;
  }
  UrlParts u = ParseUrl(url);
  return u.scheme == "https" && u.host == kWrapperHost;
}

bool AllowSubframeNavigation(const std::string& url) {
  if (url == "about:blank" || url == "about:srcdoc") {
    return true;
  }
  UrlParts u = ParseUrl(url);
  if (!HttpsDnsHost(u)) {
    return false;
  }
  for (const char* suffix : kFrameSuffixes) {
    if (HostMatches(u.host, suffix)) {
      return true;
    }
  }
  return false;
}

bool AllowSubresource(const std::string& url) {
  UrlParts u = ParseUrl(url);
  if (u.scheme == "data" || u.scheme == "blob") {
    return true;  // in-page data; no network destination
  }
  if (u.scheme != "https" && u.scheme != "wss") {
    return false;  // no plain http/ws, file, ftp, custom protocols or browser-internal schemes
  }
  bool is_public = false;
  if (ClassifyLiteral(u.host, &is_public)) {
    return is_public;
  }
  return IsDnsName(u.host);
}

}  // namespace otetv
