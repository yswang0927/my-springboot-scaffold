import usePageTitle from "@/hooks/usePageTitle";

import { useL10n } from "@/l10n";

export default function Settings() {
    const { t } = useL10n();

    usePageTitle(t("设置"));

    return (
        <h1>{t("设置页面")}</h1>
    );
}