# Netscout findings

I originally developed Netscout for myself; the plan was to specifically search for people looking for a native Android solution for Codex.

## What is Netscout?

NetScout was written in Kotlin and uses a local model to evaluate search queries.

You enter keywords such as `codex, android, nativ`, and NetScout can view the results and recognize correlations.

NetScout will remain a prototype indefinitely and is not currently intended for release.

## Found by Netscout

#### Found by Yushin-L on 2026-09-29

Yushin-L is a Korean developer who used AGENTCODI as a reference for his own project.

He states that he has neither adopted the entire AGENTCODI codebase nor implemented its sandbox in the Codex app server.

However, while examining the repository, I noticed that it had incorporated my helper patch into the build script.

[to the build script](<https://github.com/Yushin-L/android-agent/blob/88f0b141d6e1510c6b1f3a4332099aeb1a976e61/app%2Fprepare_android_payload.py>)

He mentioned AGENTCODI in his source code, but two questions arise: Is it sufficient to mention the project by name? And secondly, how much of my code did he actually copy?

I am not investigating this further at this time; however, I explicitly refer here to my Apache 2.0 license.

[To the original document by Yushin-L](<https://github.com/Yushin-L/android-agent/issues/8>)

#### Found by nono1437 on 2026-10-02

A Chinese fork of AGENTCODI, maintained by nono1437, attempted to add Chinese language support but failed due to my build dependencies.

I later adjusted his build so that it could run on his branch. I submitted a pull request to him, but he has not accepted it to this day.

[nono1437 fork](<https://github.com/nono1437/AGENTCODI>)

---

NetScout found many more results, but they still need to be evaluated by me.

NetScout still requires extensive testing before an initial release can take place.

The idea behind Netscout

NetScout is designed to help numerous developers present their products to others through targeted searches.
