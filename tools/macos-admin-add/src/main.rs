//! macos-admin-add — read a user name from standard input and add that user to
//! the local `admin` group on macOS (tested against Ventura 13 on Intel).
//!
//! The name is read from stdin rather than argv so the caller can pipe it in
//! without it showing up in `ps` output. Everything the tool does goes through
//! absolute paths to the system directory-service utilities; no shell is
//! involved, so the name is never interpreted as a command.

use std::io::{self, IsTerminal, Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};

const DSEDITGROUP: &str = "/usr/sbin/dseditgroup";
const ID: &str = "/usr/bin/id";
const GROUP: &str = "admin";
const SAFE_PATH: &str = "/usr/bin:/bin:/usr/sbin:/sbin";

/// Longest name `dscl` will accept for a local record.
const MAX_NAME_LEN: usize = 244;

const USAGE: &str = "\
macos-admin-add — add a user to the macOS admin group

Usage:
  echo <username> | sudo macos-admin-add
  sudo macos-admin-add            # prompts when stdin is a terminal

Reads exactly one user name from standard input and adds that user to the
local `admin` group. Must be run as root. Already-admin users are left alone.

Options:
  -h, --help       Show this help and exit
  -V, --version    Show the version and exit

Exit codes:
  0  user is in the admin group (added now, or already a member)
  1  bad usage or malformed input
  2  not running as root
  3  no such user on this system
  4  the group change failed or could not be verified
  5  a required system tool could not be run
";

/// Exit codes, kept in sync with `USAGE`.
mod exit {
    pub const USAGE: i32 = 1;
    pub const NOT_ROOT: i32 = 2;
    pub const NO_SUCH_USER: i32 = 3;
    pub const GROUP_CHANGE_FAILED: i32 = 4;
    pub const TOOL_FAILED: i32 = 5;
}

struct Failure {
    code: i32,
    message: String,
}

impl Failure {
    fn new(code: i32, message: impl Into<String>) -> Self {
        Failure {
            code,
            message: message.into(),
        }
    }
}

extern "C" {
    fn geteuid() -> u32;
}

fn main() {
    match run() {
        Ok(message) => println!("{message}"),
        Err(failure) => {
            eprintln!("macos-admin-add: {}", failure.message);
            std::process::exit(failure.code);
        }
    }
}

fn run() -> Result<String, Failure> {
    handle_flags()?;

    if cfg!(not(target_os = "macos")) {
        return Err(Failure::new(
            exit::TOOL_FAILED,
            "this tool only works on macOS; it drives the system directory-service utilities",
        ));
    }

    // Check this before touching stdin, so an unprivileged caller is told what
    // is wrong instead of being left at a silent prompt.
    if unsafe { geteuid() } != 0 {
        return Err(Failure::new(
            exit::NOT_ROOT,
            "must run as root — try `sudo macos-admin-add`",
        ));
    }

    let name = validate_username(&read_username()?)
        .map_err(|why| Failure::new(exit::USAGE, why))?
        .to_string();

    if !user_exists(&name)? {
        return Err(Failure::new(
            exit::NO_SUCH_USER,
            format!("no such user: {name}"),
        ));
    }

    if is_admin(&name)? {
        return Ok(format!(
            "{name} is already in the {GROUP} group; nothing to do"
        ));
    }

    add_to_admin(&name)?;

    if !is_admin(&name)? {
        return Err(Failure::new(
            exit::GROUP_CHANGE_FAILED,
            format!("{DSEDITGROUP} reported success but {name} is still not in the {GROUP} group"),
        ));
    }

    Ok(format!("{name} added to the {GROUP} group"))
}

fn handle_flags() -> Result<(), Failure> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        None => Ok(()),
        Some("-h" | "--help") => {
            print!("{USAGE}");
            std::process::exit(0);
        }
        Some("-V" | "--version") => {
            println!("macos-admin-add {}", env!("CARGO_PKG_VERSION"));
            std::process::exit(0);
        }
        Some(other) => Err(Failure::new(
            exit::USAGE,
            format!("unexpected argument `{other}` — the user name is read from standard input\n\n{USAGE}"),
        )),
    }
}

/// Read the user name from stdin. Only the first line is used; anything after
/// the first newline is ignored so a stray trailing line cannot smuggle in a
/// second name.
fn read_username() -> Result<String, Failure> {
    let mut stdin = io::stdin();
    if stdin.is_terminal() {
        eprint!("User name: ");
        let _ = io::stderr().flush();
    }

    // Bounded read: a name can never be longer than MAX_NAME_LEN, so refuse to
    // swallow an endless stream.
    let mut buffer = Vec::new();
    stdin
        .by_ref()
        .take((MAX_NAME_LEN + 2) as u64)
        .read_to_end(&mut buffer)
        .map_err(|e| Failure::new(exit::USAGE, format!("could not read standard input: {e}")))?;

    let text = String::from_utf8(buffer)
        .map_err(|_| Failure::new(exit::USAGE, "standard input is not valid UTF-8"))?;

    Ok(text.lines().next().unwrap_or("").to_string())
}

/// Accept only the shape of name macOS itself hands out: ASCII letters,
/// digits, `.`, `_` and `-`, never leading with `-` (which would read as a
/// flag to the tools below).
fn validate_username(raw: &str) -> Result<&str, String> {
    let name = raw.trim();

    if name.is_empty() {
        return Err("no user name on standard input".to_string());
    }
    if name.len() > MAX_NAME_LEN {
        return Err(format!(
            "user name is longer than {MAX_NAME_LEN} characters"
        ));
    }
    if name.starts_with('-') {
        return Err(format!("user name may not start with `-`: {name}"));
    }
    if let Some(bad) = name
        .chars()
        .find(|c| !(c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-')))
    {
        return Err(format!(
            "user name contains an unsupported character `{bad}`: {name}"
        ));
    }

    Ok(name)
}

fn user_exists(name: &str) -> Result<bool, Failure> {
    // `id` resolves local and network accounts alike, so it is a better
    // existence check than reading /Users out of the local node.
    Ok(status_of(ID, &["-u", name])? == Some(0))
}

fn is_admin(name: &str) -> Result<bool, Failure> {
    Ok(status_of(DSEDITGROUP, &["-o", "checkmember", "-m", name, GROUP])? == Some(0))
}

fn add_to_admin(name: &str) -> Result<(), Failure> {
    let output = run_tool(
        DSEDITGROUP,
        &["-o", "edit", "-a", name, "-t", "user", GROUP],
    )?;
    if output.status.success() {
        return Ok(());
    }

    let detail = first_meaningful_line(&output.stderr)
        .or_else(|| first_meaningful_line(&output.stdout))
        .unwrap_or_else(|| match output.status.code() {
            Some(code) => format!("exit status {code}"),
            None => "killed by a signal".to_string(),
        });

    Err(Failure::new(
        exit::GROUP_CHANGE_FAILED,
        format!("could not add {name} to the {GROUP} group: {detail}"),
    ))
}

/// Run `tool` and return its exit code, or `None` if it was killed.
fn status_of(tool: &str, args: &[&str]) -> Result<Option<i32>, Failure> {
    Ok(run_tool(tool, args)?.status.code())
}

fn run_tool(tool: &str, args: &[&str]) -> Result<std::process::Output, Failure> {
    if !Path::new(tool).exists() {
        return Err(Failure::new(
            exit::TOOL_FAILED,
            format!("{tool} is missing — is this really macOS?"),
        ));
    }

    Command::new(tool)
        .args(args)
        .stdin(Stdio::null())
        .env_clear()
        .env("PATH", SAFE_PATH)
        .output()
        .map_err(|e| Failure::new(exit::TOOL_FAILED, format!("could not run {tool}: {e}")))
}

fn first_meaningful_line(stream: &[u8]) -> Option<String> {
    String::from_utf8_lossy(stream)
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::validate_username;

    #[test]
    fn accepts_ordinary_names() {
        for name in ["jdoe", "j.doe", "j_doe-2", "_svc", "User99"] {
            assert_eq!(validate_username(name), Ok(name));
        }
    }

    #[test]
    fn trims_surrounding_whitespace() {
        assert_eq!(validate_username("  jdoe\t"), Ok("jdoe"));
    }

    #[test]
    fn rejects_empty_input() {
        assert!(validate_username("").is_err());
        assert!(validate_username("   ").is_err());
    }

    #[test]
    fn rejects_leading_dash() {
        assert!(validate_username("-rf").is_err());
    }

    #[test]
    fn rejects_shell_and_space_characters() {
        for name in [
            "jdoe; rm -rf /",
            "jdoe admin",
            "jdoe$(id)",
            "jdoe`id`",
            "j/doe",
            "jdoe\u{0}",
        ] {
            assert!(validate_username(name).is_err(), "should reject {name:?}");
        }
    }

    #[test]
    fn rejects_overlong_names() {
        assert!(validate_username(&"a".repeat(super::MAX_NAME_LEN + 1)).is_err());
        assert!(validate_username(&"a".repeat(super::MAX_NAME_LEN)).is_ok());
    }
}
