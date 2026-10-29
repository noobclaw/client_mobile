# TikTok selectors — status: ALL UNVERIFIED

Source: `backend/matrix/mobile_scenarios/_shared/tiktok_lib.js` (`SEL`, `CHALLENGES`, `EMPTY_PROFILE`).
Nothing has been checked on a real TikTok Android build yet. On the first device:

1. Install TikTok, log in, enable the accessibility service, and press **乾跑一次** with each task.
2. When a step logs "找不到…/打不開…", press **發送診斷**: it uploads a redacted tree (`dumpTree`).
3. Fix the matching entry below, bump the script version, re-sign.

| Key | What it must find | Guess used now | Verified |
|---|---|---|---|
| navProfile | bottom "Profile" tab | content-desc `Profile` (multi-language) | ❌ |
| handleText | `@handle` on own/other profile | text `^@[A-Za-z0-9._]{2,24}$` | ❌ |
| loggedOut | logged-out screen | "Sign up for TikTok" etc. | ❌ |
| commentBtn | comment button on a video | desc contains "comment" | ❌ |
| videoLike | like button on a video (selected = liked) | desc starts with "Like" | ❌ |
| commentList | comment sheet list | scrollable RecyclerView | ❌ |
| row layout | name = first text, body = longest text, like/reply inside the row | heuristic in `readRow` | ❌ |
| commentInput / sendBtn | comment/reply input and send | EditText / desc "Post"/"Send" | ❌ |
| followBtn / followingBtn | profile follow state | text "Follow"/"Following" | ❌ |
| profileGrid | profile works grid, first cell = newest (pinned first) | scrollable RecyclerView | ❌ |
| searchBtn / searchInput / videosTab | search flow | desc "Search", EditText, "Videos" tab | ❌ |
| CHALLENGES | captcha / rate limit / identity / ban texts | see file | ❌ |
| deep links | `openUrl` of video / profile links opens inside TikTok | Intent VIEW + setPackage | ❌ |

Known gaps against the desktop scripts (fix once selectors are known):
- comment_like: after a pasted *video* link the desktop also works that author's other videos; mobile does not yet.
- comment_like / lead_engage: swiping through an author's works does not yet detect "slid out of this author" or repeats.
- Video captions are not read yet (`video_desc` is sent empty to the AI).
