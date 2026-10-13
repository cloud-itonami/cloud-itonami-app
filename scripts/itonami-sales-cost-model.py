#!/usr/bin/env python3
"""Calculate a supplied, constant-monthly SaaS replacement scenario. No network."""
import argparse
import hashlib
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path


def amount(value):
    if isinstance(value, bool):
        raise ValueError('costs must be finite nonnegative decimal amounts')
    try:
        result = Decimal(str(value))
    except InvalidOperation as exc:
        raise ValueError('invalid cost amount') from exc
    if not result.is_finite() or result < 0:
        raise ValueError('costs must be finite nonnegative decimal amounts')
    if result > Decimal('1e15') or result.as_tuple().exponent < -6:
        raise ValueError('costs must be at most 1e15 with at most six decimal places')
    return result


def text(value):
    return format(value, 'f')


def calculate(data):
    months = data['horizon_months']
    if isinstance(months, bool) or not isinstance(months, int) or not 1 <= months <= 120:
        raise ValueError('horizon_months must be an integer in 1..120')
    currency = data['currency']
    if not isinstance(currency, str) or len(currency) != 3 or not currency.isupper() or not currency.isalpha():
        raise ValueError('currency must be a three-letter currency code')
    baseline = amount(data['baseline_monthly_cost'])
    initial = amount(data['one_time_replacement_cost'])
    scenarios = data['scenarios']
    if not isinstance(scenarios, list) or not scenarios:
        raise ValueError('at least one supplied scenario is required')
    names = set()
    rows = []
    for scenario in scenarios:
        name = scenario['name']
        if not isinstance(name, str) or not name.strip() or name in names:
            raise ValueError('scenario names must be nonempty and unique')
        names.add(name)
        replacement = amount(scenario['replacement_monthly_cost'])
        delta = baseline - replacement
        total_baseline = baseline * months
        total_replacement = initial + replacement * months
        net = total_baseline - total_replacement
        # Month 0 pays the setup cost; later rows contain incremental savings.
        flows = [{'month': 0, 'net_savings': text(-initial), 'cumulative_net_savings': text(-initial)}]
        for month in range(1, months + 1):
            flows.append({'month': month, 'net_savings': text(delta),
                          'cumulative_net_savings': text(-initial + delta * month)})
        payback = next((row['month'] for row in flows if Decimal(row['cumulative_net_savings']) >= 0), None)
        recurring_baseline = baseline * 12
        recurring_cost = replacement * 12
        rows.append({
            'name': name, 'replacement_monthly_cost': text(replacement),
            'baseline_horizon_cost': text(total_baseline),
            'replacement_horizon_cost': text(total_replacement),
            'net_savings': text(net),
            'savings_rate_percent': text(net / total_baseline * 100) if total_baseline else None,
            'recurring_annual_replacement_cost': text(recurring_cost),
            'recurring_annual_net_savings': text(recurring_baseline - recurring_cost),
            'payback_month': payback, 'monthly_cash_flows': flows,
        })
    digest = hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    return {'schema': 'itonami.sales.cost-model.v1', 'currency': currency,
            'horizon_months': months, 'input_sha256': digest,
            'evidence_status': 'calculated-from-supplied-inputs-not-measured-savings',
            'assumptions': ['constant monthly costs', 'setup paid at month 0',
                            'avoidable baseline fees stop in month 1',
                            'all retained fees and operating costs included in replacement_monthly_cost',
                            'single currency and consistent tax basis; no FX conversion'],
            'scenarios': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        receipt = calculate(json.loads(args.input.read_text()))
    except (ValueError, KeyError, TypeError) as exc:
        parser.error(str(exc))
    rendered = json.dumps(receipt, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(rendered)
    else:
        print(rendered, end='')


if __name__ == '__main__':
    main()
