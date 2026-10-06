# MiniGit — 5-minute live demo

Everything below was run against the built jar, and the output shown is real (hashes and dates will
differ on your machine). Timings are a rough guide for a ~5 minute talk.

## Before the talk (once)

```bash
mvn clean package            # builds target/minigit.jar and runs the 144 tests
```

Open a terminal in the project root and put the launcher on your PATH.

**Bash / Git Bash / macOS / Linux**

```bash
export PATH="$PWD/bin:$PATH"
minigit --version            # MiniGit version 1.0.0
```

**PowerShell**

```powershell
$env:PATH = "$PWD\bin;$env:PATH"
minigit --version
```

> **PowerShell tip:** Windows PowerShell 5.1 writes `>` redirects as UTF-16, which MiniGit (like Git) sees as
> a *binary* file. Use `Set-Content` instead of `echo ... > file` — each step below shows both forms.

Start from a clean, empty folder:

```bash
mkdir /tmp/minigit-demo && cd /tmp/minigit-demo        # bash
```
```powershell
mkdir $env:TEMP\minigit-demo; cd $env:TEMP\minigit-demo   # PowerShell
```

---

## 1. Initialize a repository (0:00)

```bash
minigit init
minigit config user.name "Lavya"
minigit config user.email "lavya@example.com"
ls -a .minigit              # HEAD  config  index  logs  objects  refs
```

```text
Initialized empty MiniGit repository in /tmp/minigit-demo/.minigit
```

*Say:* everything MiniGit knows lives in `.minigit`; `HEAD` points at the `main` branch.

## 2. Create a file (0:30)

```bash
echo "Hello world" > README.md                 # bash
```
```powershell
Set-Content README.md "Hello world"            # PowerShell
```

```bash
minigit status
```

```text
On branch main
No commits yet

Untracked files:
  README.md

nothing added to commit but untracked files present (use "minigit add" to track)
```

## 3. Add the file (1:00)

```bash
minigit add README.md
minigit status
```

```text
Added README.md

On branch main
No commits yet

Changes to be committed:
  new file: README.md
```

*Say:* `add` hashed the file with SHA-256 and stored it as a blob.

```bash
find .minigit/objects -type f          # PowerShell: Get-ChildItem .minigit\objects -Recurse -File
```

shows one file named by the 62 remaining hash characters inside a 2-character directory.

## 4. Commit (1:30)

```bash
minigit commit -m "Initial commit"
```

```text
[main 0603521] Initial commit
 1 file changed
```

## 5. Modify the file (2:00)

```bash
echo "Hello MiniGit" > README.md              # bash
```
```powershell
Set-Content README.md "Hello MiniGit"        # PowerShell
```

## 6. View status (2:15)

```bash
minigit status
```

```text
On branch main

Changes not staged for commit:
  modified: README.md
```

## 7. View the diff (2:30)

```bash
minigit diff
```

```text
--- README.md
+++ README.md
@@ -1,1 +1,1 @@
-Hello world
+Hello MiniGit
```

## 8. Commit the modification (2:45)

```bash
minigit add README.md
minigit diff --cached          # same diff, now from the staging area
minigit commit -m "Update README"
minigit log --oneline
```

```text
[main 88804f2] Update README
 1 file changed
88804f2 Update README
0603521 Initial commit
```

## 9. Create a branch (3:15)

```bash
minigit branch feature
minigit branch
```

```text
Created branch 'feature' at 88804f2
  feature
* main
```

*Say:* a branch is one 64-character file — nothing was copied.

## 10. Check out the branch (3:30)

```bash
minigit checkout feature
```

```text
Switched to branch 'feature'
```

## 11. Make a branch-specific change (3:45)

```bash
echo "Only on the feature branch" > feature.txt                 # bash
```
```powershell
Set-Content feature.txt "Only on the feature branch"            # PowerShell
```

```bash
minigit add .
minigit commit -m "Add feature file"
```

```text
Added feature.txt
[feature 618e40a] Add feature file
 1 file changed
```

## 12. View the log (4:15)

```bash
minigit log
```

```text
commit 618e40ac16c1d6341d6b5676e91cf3917ccee08bde54faaf9524166b145db1dd
Author: Lavya <lavya@example.com>
Date:   2026-10-06 23:22

    Add feature file

commit 88804f2cb1a8112ed41fc017c2d7d9e9979804cb17a7eb34f19a81278ea39b9e
Author: Lavya <lavya@example.com>
...
```

*Say:* each commit's hash covers its parent's hash, so history cannot be altered unnoticed.

## 13. Check out `main` (4:30)

```bash
minigit checkout main
ls                              # PowerShell: dir
```

```text
Switched to branch 'main'
README.md
```

## 14. Show the different repository state (4:40)

`feature.txt` has disappeared from the working directory — it only exists on `feature`.

```bash
minigit log --oneline           # only 2 commits on main
minigit checkout feature
ls                              # README.md  feature.txt  — it is back
```

```text
Switched to branch 'feature'
```

**Bonus (if time allows) — the safety net.** Go back to `main`, create your own untracked `feature.txt`,
then try to switch to the branch that tracks a different `feature.txt`:

```bash
minigit checkout main
echo "my local notes" > feature.txt            # PowerShell: Set-Content feature.txt "my local notes"
minigit checkout feature
```

```text
fatal: uncommitted changes would be overwritten by checkout:
	feature.txt
Commit or discard your changes before switching branches.
```

Your file is untouched and you are still on `main`. Remove it (`rm feature.txt`) and the checkout works.

---

## Reset between runs

```bash
cd .. && rm -rf /tmp/minigit-demo               # PowerShell: Remove-Item -Recurse -Force $env:TEMP\minigit-demo
```
