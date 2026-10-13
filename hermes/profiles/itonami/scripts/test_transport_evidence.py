import io, json, unittest
from unittest.mock import patch
import transport_evidence as evidence

class EvidenceTest(unittest.TestCase):
    def run_rows(self, rows, countries):
        responses = [io.StringIO(json.dumps([{}, rows])), io.StringIO(json.dumps([{}, countries]))]
        with patch.object(evidence.urllib.request, 'urlopen', side_effect=responses):
            return evidence.try_lpi()

    def test_only_valid_latest_country_observations(self):
        def row(country, value, date='2022', indicator='LP.LPI.OVRL.XQ'):
            return dict(countryiso3code=country, value=value, date=date, indicator=dict(id=indicator))
        countries = [dict(id='JPN', region=dict(value='East Asia')), dict(id='WLD', region=dict(value='Aggregates'))]
        rows = [row('JPN', 3.8), row('JPN', 3.9, '2023'), row('WLD', 3.1), row('XXX', 2.0), row('JPN', True, '2024'), row('JPN', float('nan'), '2024'), row('JPN', 9, '2024'), row('JPN', 2, '2024', 'OTHER')]
        self.assertEqual(self.run_rows(rows, countries), {'JPN': (3.9, '2023')})

    def test_country_lookup_failure_is_unmeasured(self):
        with patch.object(evidence.urllib.request, 'urlopen', side_effect=[io.StringIO('[{}, []]'), OSError('unavailable')]):
            self.assertIsNone(evidence.try_lpi())

    def test_unrelated_document_is_unmeasured(self):
        with patch.object(evidence.urllib.request, 'urlopen', return_value=io.StringIO(json.dumps(dict(body='transport logistics', number=1205)))):
            self.assertIsNone(evidence.try_lpi())

if __name__ == '__main__':
    unittest.main()
