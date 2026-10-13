import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('sales_cost_model', Path(__file__).parents[1] / 'scripts/itonami-sales-cost-model.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CostModelTests(unittest.TestCase):
    def data(self, baseline=100000, initial=300000, monthly=30000):
        return {'currency': 'JPY', 'horizon_months': 12,
                'baseline_monthly_cost': baseline, 'one_time_replacement_cost': initial,
                'scenarios': [{'name': 'ベース', 'replacement_monthly_cost': monthly}]}

    def test_setup_cost_and_month_end_payback(self):
        row = module.calculate(self.data())['scenarios'][0]
        self.assertEqual((row['replacement_horizon_cost'], row['net_savings'], row['savings_rate_percent']), ('660000', '540000', '45.00'))
        self.assertEqual(row['recurring_annual_net_savings'], '840000')
        self.assertEqual(row['payback_month'], 5)
        self.assertEqual(row['monthly_cash_flows'][4]['cumulative_net_savings'], '-20000')
        self.assertEqual(row['monthly_cash_flows'][5]['cumulative_net_savings'], '50000')
        self.assertEqual(row['monthly_cash_flows'][12]['cumulative_net_savings'], row['net_savings'])

    def test_loss_and_zero_baseline_do_not_invent_savings(self):
        row = module.calculate(self.data(baseline=0, monthly=10000))['scenarios'][0]
        self.assertEqual(row['net_savings'], '-420000')
        self.assertIsNone(row['savings_rate_percent'])
        self.assertIsNone(row['payback_month'])

    def test_exact_decimal_currency_units(self):
        row = module.calculate(self.data(baseline='0.30', initial='0.25', monthly='0.20'))['scenarios'][0]
        self.assertEqual(row['net_savings'], '0.95')
        self.assertEqual(row['payback_month'], 3)

    def test_untrusted_amounts_and_duplicate_scenarios_fail(self):
        for value in [True, -1, 'NaN', 'Infinity', '1e1000', '0.0000001']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                module.calculate(self.data(monthly=value))
        data = self.data(); data['scenarios'] *= 2
        with self.assertRaises(ValueError):
            module.calculate(data)


if __name__ == '__main__':
    unittest.main()
