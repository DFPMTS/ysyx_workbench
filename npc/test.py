a=[
5.48030437275239,
3.18199804816311,
4.08132283603079,
4.46448289923638,
3.88510134953492,
2.72654632405554,
5.19474380111327,
4.86586747016798,
3.39488255176045,


]


# get Geo Average
from math import exp, log
def geo_average(a):
    return exp(sum(log(x) for x in a) / len(a))

if __name__ == "__main__":
    print(geo_average(a))