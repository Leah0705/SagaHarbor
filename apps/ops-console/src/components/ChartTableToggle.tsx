import type { ReactNode } from "react";

// Every chart in this console pairs with a table of the same data, so screen-reader users
// and anyone who cannot read a visual trend still get the numbers.
export function ChartTableToggle({ chart, table }: { chart: ReactNode; table: ReactNode }) {
  return (
    <div>
      {chart}
      <details>
        <summary>View as table</summary>
        {table}
      </details>
    </div>
  );
}
