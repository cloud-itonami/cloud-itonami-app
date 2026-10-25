# Opt-in collection profiles hosted on Murakumo

Collection uses the existing durable Bot profile and existing tool admission
path. The resident offers `collection_profile` and `collection_propose` only
when its operator-reviewed configuration contains a grant under:

```edn
{:bots {:collection
        {:grants
         {[organization-id account-id bot-id]
          {:enabled? true :owner-did owner-did :profile-revision reviewed-revision
           :seeds #{approved-public-url} :max-pages 5 :max-depth 1}}}}}
```

The account ID is not inferred to be a DID. The approved grant binds the actual
owner DID. Each call checks organization/account/bot and exact durable profile
revision. Changing SOUL requires a newly reviewed hosting revision.

`collection_profile` exports only SOUL, bot ID, owner DID and the restricted
tools (`web/crawl`, `web/index`) as `web/collector-profile-v1`. No USER/MEMORY,
private workspace or credential is copied to the host. Export is prepared,
not installed, enrolled or paid. Murakumo computes the snapshot CID and requires
a separately admitted supplier/node grant before installing it.

`collection_propose` is a write offered through the normal Bot approval path.
It records one bounded proposal per owner/organization/bot. The model can pick
from admitted seeds and lower page/depth ceilings; it cannot submit arbitrary
targets, prices, budgets, commands, credentials or payment events. A proposal
does not approve a Murakumo campaign. Existing profile documents never grant
execution or financial authority.

The Murakumo operator profile is `hermes/profiles/web-plane` in the Murakumo
repository. A host performs one local inference and deterministic crawl/index
tools, shares verified result blocks, and earns KUMO only through the native
operator's verified settlement. Participant-grant registration, production
coordinator wiring, storage and native finality must be assembled and witnessed
before this is an end-to-end user service. This patch installs no user grants.

Focused portable check:

```sh
kbb -M:collection-test
```

The namespace is also registered in the existing full and portable test lists.
The focused gate tests owner isolation, bounded proposals and private-memory
exclusion without loading the resident's JVM-only dependencies.
