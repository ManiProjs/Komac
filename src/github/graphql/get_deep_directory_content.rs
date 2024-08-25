use crate::github::graphql::get_directory_content::GetDirectoryContentVariablesFields;
use crate::github::graphql::github_schema::github_schema as schema;

#[derive(cynic::QueryFragment)]
pub struct Tree {
    #[cynic(flatten)]
    pub entries: Vec<TreeEntry>,
}

#[derive(cynic::QueryFragment)]
pub struct TreeEntry {
    pub name: String,
    pub object: Option<DeepGitObjectNested>,
}

#[derive(cynic::QueryFragment)]
#[cynic(graphql_type = "Query", variables = "GetDirectoryContentVariables")]
pub struct GetDeepDirectoryContent {
    #[arguments(owner: $owner, name: $name)]
    pub repository: Option<Repository>,
}

#[derive(cynic::QueryFragment)]
#[cynic(variables = "GetDirectoryContentVariables")]
pub struct Repository {
    #[arguments(expression: $expression)]
    pub object: Option<DeepGitObject>,
}

#[derive(cynic::InlineFragments)]
#[cynic(graphql_type = "GitObject")]
pub enum DeepGitObject {
    Tree(Tree),
    #[cynic(fallback)]
    Unknown,
}

impl DeepGitObject {
    pub fn into_entries(self) -> Option<Vec<TreeEntry>> {
        match self {
            Self::Tree(tree) => Some(tree.entries),
            Self::Unknown => None,
        }
    }
}

#[derive(cynic::QueryFragment)]
#[cynic(graphql_type = "Tree")]
pub struct TreeNested {
    #[cynic(flatten)]
    pub entries: Vec<TreeEntryNested>,
}

#[derive(cynic::QueryFragment)]
#[cynic(graphql_type = "TreeEntry")]
pub struct TreeEntryNested {
    #[cynic(rename = "type")]
    pub type_: String,
}

#[derive(cynic::InlineFragments)]
#[cynic(graphql_type = "GitObject")]
pub enum DeepGitObjectNested {
    TreeNested(TreeNested),
    #[cynic(fallback)]
    Unknown,
}

impl DeepGitObjectNested {
    pub fn into_entries(self) -> Option<Vec<TreeEntryNested>> {
        match self {
            Self::TreeNested(tree) => Some(tree.entries),
            Self::Unknown => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::github::github_client::{MICROSOFT, WINGET_PKGS};
    use crate::github::graphql::get_deep_directory_content::GetDeepDirectoryContent;
    use crate::github::graphql::get_directory_content::GetDirectoryContentVariables;
    use cynic::QueryBuilder;
    use indoc::indoc;

    #[test]
    fn get_deep_directory_content_output() {
        const GET_DEEP_DIRECTORY_CONTENT_QUERY: &str = indoc! {r#"
            query GetDeepDirectoryContent($owner: String!, $name: String!, $expression: String!) {
              repository(owner: $owner, name: $name) {
                object(expression: $expression) {
                  __typename
                  ... on Tree {
                    entries {
                      name
                      object {
                        __typename
                        ... on Tree {
                          entries {
                            type
                          }
                        }
                      }
                    }
                  }
                }
              }
            }

        "#};

        let operation = GetDeepDirectoryContent::build(GetDirectoryContentVariables {
            owner: MICROSOFT,
            name: WINGET_PKGS,
            expression: "",
        });

        assert_eq!(operation.query, GET_DEEP_DIRECTORY_CONTENT_QUERY);
    }
}
