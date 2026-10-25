// Browser destination and navigation policy (brief sections 12.2 and 16.2). Address classification mirrors
// com.otectus.otetv.core.net.IpPolicy so the browser and the direct-media fetcher refuse the same destinations.
#ifndef OTETV_BROWSER_WORKER_POLICY_H_
#define OTETV_BROWSER_WORKER_POLICY_H_

#include <cstdint>
#include <string>

struct sockaddr;

namespace otetv {

constexpr const char* kWrapperOrigin = "https://com.otectus.otetv";
constexpr const char* kWrapperHost = "com.otectus.otetv";

// True only for globally routable unicast addresses: loopback, private, CGNAT, link-local, cloud metadata,
// multicast, documentation, benchmarking, reserved and IPv6 forms embedding a non-public IPv4 are all refused.
bool IsPublicV4(const uint8_t a[4]);
bool IsPublicV6(const uint8_t a[16]);
bool IsPublicAddress(const sockaddr* address);

// A syntactically valid DNS name (letters, digits, hyphens, dots; 1..253 chars) that is not an IP literal.
bool IsDnsName(const std::string& host);
// If |host| is an IPv4/IPv6 literal, sets |is_public| and returns true.
bool ClassifyLiteral(const std::string& host, bool* is_public);

// host == suffix, or host ends with "." + suffix (ASCII, already lower-cased by Chromium's URL parser).
bool HostMatches(const std::string& host, const std::string& suffix);

// Parsed pieces of an absolute URL, enough for policy decisions.
struct UrlParts {
  std::string scheme;
  std::string host;
};
UrlParts ParseUrl(const std::string& url);

// The top-level frame may only show the first-party wrapper (and about:blank while it loads).
bool AllowMainFrameNavigation(const std::string& url);
// Embedded frames: the official player and the provider's own consent/advertising frames, https only.
bool AllowSubframeNavigation(const std::string& url);
// Any subresource must be https (or an in-page scheme); IP literals must be public. Hostname destinations are
// checked again, after DNS resolution, by the guarded proxy.
bool AllowSubresource(const std::string& url);

}  // namespace otetv

#endif  // OTETV_BROWSER_WORKER_POLICY_H_
