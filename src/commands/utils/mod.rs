use anstream::println;
use camino::Utf8Path;
use chrono::Local;
use color_eyre::Result;
use futures_util::{stream, StreamExt, TryStreamExt};
use inquire::{Confirm, Select};
use owo_colors::OwoColorize;
use std::env;
use std::str::FromStr;
use std::time::Duration;
use strum::{Display, EnumIter, IntoEnumIterator};
use tokio::fs;
use tokio::fs::File;
use tokio::io::AsyncWriteExt;

use crate::editor::Editor;
use crate::github::graphql::get_existing_pull_request::PullRequest;
use crate::manifests::print_changes;
use crate::prompts::prompt::handle_inquire_error;
use crate::types::package_identifier::PackageIdentifier;
use crate::types::package_version::PackageVersion;

pub const SPINNER_TICK_RATE: Duration = Duration::from_millis(50);

pub const SPINNER_SLOW_TICK_RATE: Duration = Duration::from_millis(100);

pub fn prompt_existing_pull_request(
    identifier: &PackageIdentifier,
    version: &PackageVersion,
    pull_request: &PullRequest,
) -> Result<bool> {
    let created_at = pull_request.created_at.with_timezone(&Local);
    println!(
        "There is already {} pull request for {identifier} {version} that was created on {} at {}",
        pull_request.state,
        created_at.date_naive(),
        created_at.time()
    );
    println!("{}", pull_request.url.blue());
    let proceed = if env::var("CI").is_ok_and(|ci| bool::from_str(&ci) == Ok(true)) {
        false
    } else {
        Confirm::new("Would you like to proceed?")
            .prompt()
            .map_err(handle_inquire_error)?
    };
    Ok(proceed)
}

pub fn prompt_submit_option(
    changes: &mut [(String, String)],
    submit: bool,
    identifier: &PackageIdentifier,
    version: &PackageVersion,
    dry_run: bool,
) -> Result<SubmitOption> {
    let mut submit_option;
    loop {
        print_changes(changes.iter().map(|(_, content)| content.as_str()));

        submit_option = if dry_run {
            SubmitOption::Exit
        } else if submit {
            SubmitOption::Submit
        } else {
            Select::new(
                &format!("What would you like to do with {identifier} {version}?"),
                SubmitOption::iter().collect(),
            )
            .prompt()
            .map_err(handle_inquire_error)?
        };

        if submit_option == SubmitOption::Edit {
            Editor::new(changes).run()?;
        } else if submit_option == SubmitOption::SaveToFile {
            let slice = &identifier[..1];
            println!("Not implemented yet");
        } else {
            break;
        }
    }
    Ok(submit_option)
}

#[derive(Display, EnumIter, Eq, PartialEq)]
pub enum SubmitOption {
    SaveToFile,
    Submit,
    Edit,
    Exit,
}

pub async fn write_changes_to_dir(changes: &[(String, String)], output: &Utf8Path) -> Result<()> {
    fs::create_dir_all(output).await?;
    stream::iter(changes.iter())
        .map(|(path, content)| async move {
            if let Some(file_name) = Utf8Path::new(path).file_name() {
                let mut file = File::create(output.join(file_name)).await?;
                file.write_all(content.as_bytes()).await?;
            }
            Ok::<(), color_eyre::eyre::Error>(())
        })
        .buffer_unordered(2)
        .try_collect()
        .await
}

pub async fn save_to_file(firstchar: &str, author: &str, name: &str, version: &str, manifest: &str, outpath: &str) -> std::io::Result<()> {
    let mut file = fs::File::create(format!("{}/manifests/{}/{}/{}/{}.yaml", outpath, firstchar, author, name, version)).await?;
    file.write_all(manifest.as_bytes()).await
}
