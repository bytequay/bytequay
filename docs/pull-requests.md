# Pull requests

The pull-request screen has a filterable list on the left and the selected PR
on the right.

- **Open** shows active account-relevant PRs and PRs from currently monitored repositories.
- **My work** shows pull requests you authored.
- **Review** shows pull requests awaiting your review.
- **Monitored** shows pull requests you chose to monitor with the bell.
- **Reviewed** shows review work you completed.
- **Closed** shows merged and closed pull requests.

Use **More filters** if a tab is hidden. Search by title, `#number`, or `@author`;
clear search to return to the selected filter.

The **PR** view provides the description, checks, and recent GitHub activity.
**WorkBench → Changes** shows file patches. Select a diff line or range and
**Add to review**, then inspect and publish feedback through **Submit review**.
**WorkBench → Summary → Start review** starts a private agent review when the
repository and Review provider are ready.

Comments, reactions, approvals, and merge actions write to GitHub. Private agent
findings stay private until you explicitly publish them. Development-task
automation has its own accepted publication scope; monitoring a PR does not
enable CI repair.

See the [PR guide](documentation/README.md#pull-requests),
[private review guide](documentation/README.md#private-review), and
[delivery and CI guide](documentation/README.md#delivery-and-ci) for steps.
